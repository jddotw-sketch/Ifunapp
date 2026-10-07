from __future__ import annotations

import traceback
from threading import Thread

from kivy.app import App
from kivy.clock import Clock, mainthread
from kivy.uix.label import Label

from android import activity, mActivity
from jnius import autoclass

from downloader import extract_ifunny_url, fetch_post

Intent = autoclass("android.content.Intent")
ContentValues = autoclass("android.content.ContentValues")
Environment = autoclass("android.os.Environment")
Toast = autoclass("android.widget.Toast")

# IMPORTANT: PyJNIus nested Java classes must be loaded with '$'.
# Using MediaStore.MediaColumns / MediaStore.Downloads does not reliably work.
MediaStoreMediaColumns = autoclass("android.provider.MediaStore$MediaColumns")
MediaStoreDownloads = autoclass("android.provider.MediaStore$Downloads")


class IFunnyShareApp(App):
    def build(self):
        self.label = Label(
            text="Share an iFunny link to this app.",
            halign="center",
            valign="middle",
        )
        self.label.bind(size=lambda instance, value: setattr(instance, "text_size", value))
        return self.label

    def on_start(self):
        activity.bind(on_new_intent=self.on_new_intent)
        Clock.schedule_once(lambda _dt: self.handle_intent(mActivity.getIntent()), 0.1)

    def on_stop(self):
        try:
            activity.unbind(on_new_intent=self.on_new_intent)
        except Exception:
            pass

    def on_new_intent(self, intent):
        self.handle_intent(intent)

    def handle_intent(self, intent):
        if not intent or intent.getAction() != Intent.ACTION_SEND:
            return

        mime = str(intent.getType() or "")
        if mime and not mime.startswith("text/"):
            self.fail(f"Unsupported shared type: {mime}")
            return

        text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if not text:
            self.fail("The shared item did not contain a text link.")
            return

        # Consume the share so Activity recreation cannot download it twice.
        intent.removeExtra(Intent.EXTRA_TEXT)

        try:
            url = extract_ifunny_url(str(text))
        except Exception as exc:
            self.fail(str(exc))
            return

        self.label.text = f"Downloading...\n{url}"
        Thread(target=self.download_worker, args=(url,), daemon=True).start()

    def download_worker(self, url: str):
        try:
            data, filename, mime = fetch_post(url, crop_logo=True)
            self.set_status(f"Downloaded media. Saving...\n{filename}")
            self.save_to_downloads(data, filename, mime)
            self.success(filename)
        except Exception as exc:
            traceback.print_exc()
            self.fail(f"Download failed:\n{type(exc).__name__}: {exc}")

    def save_to_downloads(self, data: bytes, filename: str, mime: str):
        resolver = mActivity.getApplicationContext().getContentResolver()

        values = ContentValues()
        values.put(MediaStoreMediaColumns.DISPLAY_NAME, filename)
        values.put(MediaStoreMediaColumns.MIME_TYPE, mime)
        values.put(
            MediaStoreMediaColumns.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS + "/iFunny",
        )
        values.put(MediaStoreMediaColumns.IS_PENDING, 1)

        uri = resolver.insert(MediaStoreDownloads.EXTERNAL_CONTENT_URI, values)
        if uri is None:
            raise RuntimeError("Android could not create the Download/iFunny entry.")

        stream = None
        try:
            stream = resolver.openOutputStream(uri)
            if stream is None:
                raise RuntimeError("Android could not open the new download file.")

            # PyJNIus maps Python bytearray to Java byte[]. Chunking keeps large
            # videos from creating one huge Java-side temporary array.
            chunk_size = 1024 * 1024
            for offset in range(0, len(data), chunk_size):
                stream.write(bytearray(data[offset : offset + chunk_size]))
            stream.flush()
        except Exception:
            try:
                resolver.delete(uri, None, None)
            except Exception:
                pass
            raise
        finally:
            if stream is not None:
                stream.close()

        complete = ContentValues()
        complete.put(MediaStoreMediaColumns.IS_PENDING, 0)
        resolver.update(uri, complete, None, None)

    @mainthread
    def set_status(self, message: str):
        self.label.text = message

    @mainthread
    def success(self, filename: str):
        self.label.text = f"Saved to Download/iFunny\n{filename}"
        Toast.makeText(mActivity, "iFunny download complete", Toast.LENGTH_SHORT).show()
        Clock.schedule_once(lambda _dt: mActivity.finishAndRemoveTask(), 1.5)

    @mainthread
    def fail(self, message: str):
        # Deliberately DO NOT auto-close on failure. Keep the exact error visible
        # so the next problem can be diagnosed instead of disappearing.
        self.label.text = message + "\n\nPress Back to close."
        Toast.makeText(mActivity, "iFunny download failed", Toast.LENGTH_LONG).show()


IFunnyShareApp().run()
