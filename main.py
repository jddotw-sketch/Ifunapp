from threading import Thread, Lock
import traceback
from kivy.app import App
from kivy.clock import Clock, mainthread
from kivy.uix.label import Label
from android import activity, mActivity
from jnius import autoclass
from downloader import extract_ifunny_url, fetch_post
from storage import save_to_downloads

Intent = autoclass('android.content.Intent')


class IFunnyShareApp(App):
    def build(self):
        self.busy = Lock()
        self.label = Label(text='iFunny Downloader v1.0.4\nShare an iFunny link to this app.',
                           halign='center', valign='middle')
        self.label.bind(size=lambda instance, value: setattr(instance, 'text_size', value))
        return self.label

    def on_start(self):
        activity.bind(on_new_intent=self.on_new_intent)
        Clock.schedule_once(lambda dt: self.handle_intent(mActivity.getIntent()), 0.1)

    def on_stop(self):
        activity.unbind(on_new_intent=self.on_new_intent)

    def on_new_intent(self, intent):
        self.handle_intent(intent)

    def handle_intent(self, intent):
        if intent is None or intent.getAction() != Intent.ACTION_SEND:
            return
        text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if text is None:
            self.set_status('The shared item did not contain a text link.\nPress Back to close.')
            return
        try:
            url = extract_ifunny_url(str(text))
        except Exception as error:
            self.set_status(str(error))
            return
        if not self.busy.acquire(blocking=False):
            self.set_status('A download is already in progress. Share again after it finishes.')
            return
        intent.removeExtra(Intent.EXTRA_TEXT)
        self.set_status('Downloading...\n' + url)
        Thread(target=self.download_worker, args=(url,), daemon=True).start()

    def download_worker(self, url):
        try:
            data, filename, mime = fetch_post(url, crop_logo=True)
            save_to_downloads(data, filename, mime, mActivity.getApplicationContext(),
                              autoclass, self.set_status)
            self.success(filename)
        except Exception as error:
            traceback.print_exc()
            self.set_status(f'Download failed:\n{type(error).__name__}: {error}\n\nPress Back to close.')
        finally:
            self.busy.release()

    @mainthread
    def set_status(self, message):
        self.label.text = message

    @mainthread
    def success(self, filename):
        self.label.text = 'Saved to Download/iFunny\n' + filename
        Clock.schedule_once(lambda dt: mActivity.finishAndRemoveTask(), 1.5)


IFunnyShareApp().run()
