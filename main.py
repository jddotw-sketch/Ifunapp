from __future__ import annotations

from threading import Thread

from kivy.app import App
from kivy.clock import Clock, mainthread
from kivy.uix.label import Label

from android import activity, mActivity
from jnius import autoclass

from downloader import extract_ifunny_url, fetch_post

Intent = autoclass("android.content.Intent")
ContentValues = autoclass("android.content.ContentValues")
MediaStore = autoclass("android.provider.MediaStore")
Environment = autoclass("android.os.Environment")
Toast = autoclass("android.widget.Toast")


class IFunnyShareApp(App):
    def build(self):
        self.label = Label(text="Share an iFunny link to this app.")
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
        if intent.getType() != "text/plain":
            self.fail("Share a text iFunny link.")
            return
        text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if not text:
            self.fail("The shared item did not contain text.")
            return
        # Consume the share payload so an Activity recreation (for example, a
        # rotation) cannot trigger the same download a second time.
        intent.removeExtra(Intent.EXTRA_TEXT)
        try:
            url = extract_ifunny_url(str(text))
        except Exception as exc:
            self.fail(str(exc))
            return
        self.label.text = "Downloading…"
        Thread(target=self.download_worker, args=(url,), daemon=True).start()

    def download_worker(self, url: str):
        try:
            data, filename, mime = fetch_post(url, crop_logo=True)
            self.save_to_downloads(data, filename, mime)
            self.success(filename)
        except Exception as exc:
            self.fail(f"Download failed: {exc}")

    def save_to_downloads(self, data: bytes, filename: str, mime: str):
        resolver = mActivity.getContentResolver()
        values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime)
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/iFunny")
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)

        uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if uri is None:
            raise RuntimeError("Android could not create the download file.")

        stream = None
        try:
            stream = resolver.openOutputStream(uri)
            if stream is None:
                raise RuntimeError("Android could not open the download file.")
            stream.write(data)
            stream.flush()
        except Exception:
            resolver.delete(uri, None, None)
            raise
        finally:
            if stream is not None:
                stream.close()

        complete = ContentValues()
        complete.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, complete, None, None)

    @mainthread
    def success(self, filename: str):
        self.label.text = f"Saved to Download/iFunny\n{filename}"
        Toast.makeText(mActivity, "iFunny download complete", Toast.LENGTH_SHORT).show()
        Clock.schedule_once(lambda _dt: mActivity.finishAndRemoveTask(), 1.0)

    @mainthread
    def fail(self, message: str):
        self.label.text = message
        Toast.makeText(mActivity, message, Toast.LENGTH_LONG).show()
        Clock.schedule_once(lambda _dt: mActivity.finishAndRemoveTask(), 2.5)


IFunnyShareApp().run()
