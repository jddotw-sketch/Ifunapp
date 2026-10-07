"""Save using Java streams, with transactional MediaStore cleanup (Android 10+)."""
import os
import tempfile
from contextlib import ExitStack


def save_to_downloads(data, filename, mime, context, autoclass, status):
    if not data:
        raise RuntimeError('Downloaded file is empty.')
    if autoclass('android.os.Build$VERSION').SDK_INT < 29:
        raise RuntimeError('This version requires Android 10 or newer.')
    resolver = context.getContentResolver()
    ContentValues = autoclass('android.content.ContentValues')
    Downloads = autoclass('android.provider.MediaStore$Downloads')
    FileInputStream = autoclass('java.io.FileInputStream')
    FileUtils = autoclass('android.os.FileUtils')
    # String values avoid PyJNIus ambiguity between ContentValues.put overloads.
    pending = ContentValues()
    pending.put('_display_name', filename)
    pending.put('mime_type', mime)
    pending.put('relative_path', 'Download/iFunny/')
    pending.put('is_pending', '1')
    uri = None
    cache_path = None
    completed = False
    try:
        status('Preparing cache file...\n' + filename)
        with tempfile.NamedTemporaryFile(dir=str(context.getCacheDir().getAbsolutePath()),
                                         prefix='ifunny-', suffix='.tmp', delete=False) as f:
            cache_path = f.name
            f.write(data)
        expected = os.path.getsize(cache_path)
        status('Creating Download/iFunny file...\n' + filename)
        uri = resolver.insert(Downloads.EXTERNAL_CONTENT_URI, pending)
        if uri is None:
            raise RuntimeError('Android could not create the download entry.')
        status('Opening Android output stream...\n' + filename)
        # ExitStack ensures both streams close before publishing the entry.
        with ExitStack() as stack:
            output = resolver.openOutputStream(uri, 'w')
            if output is None:
                raise RuntimeError('Android could not open the download file.')
            stack.callback(output.close)
            source = FileInputStream(cache_path)
            stack.callback(source.close)
            status('Copying file with Android...\n' + filename)
            copied = FileUtils.copy(source, output)
            output.flush()
            if copied != expected:
                raise RuntimeError(f'Incomplete copy: {copied} of {expected} bytes.')
        status('Publishing download...\n' + filename)
        ready = ContentValues()
        ready.put('is_pending', '0')
        if resolver.update(uri, ready, None, None) != 1:
            raise RuntimeError('Android could not publish the download.')
        completed = True
        return str(uri.toString())
    finally:
        if uri is not None and not completed:
            try:
                resolver.delete(uri, None, None)
            except Exception:
                pass
        if cache_path:
            try:
                os.unlink(cache_path)
            except OSError:
                pass
