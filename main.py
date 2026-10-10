"""Setup screen only. Normal shares use the native ShareActivity and service."""
from kivy.app import App
from kivy.clock import Clock
from kivy.uix.label import Label
from android import mActivity
from android.permissions import request_permissions
from jnius import autoclass


class IFunnySetupApp(App):
    def build(self):
        self.label = Label(text='iFunny Downloader v1.1.2\n\nShare a post to download in the background.\n'
                           'Allow notifications to see when it finishes.\nFiles: Download/iFunny',
                           halign='center', valign='middle')
        self.label.bind(size=lambda widget, size: setattr(widget, 'text_size', size))
        return self.label

    def on_start(self):
        Clock.schedule_once(self.setup, 0.1)

    def setup(self, dt):
        Intent = autoclass('android.content.Intent')
        text = mActivity.getIntent().getStringExtra(Intent.EXTRA_TEXT)
        if text:
            # Initial share required extraction; pass it back to the now-ready native receiver.
            intent = Intent(mActivity, autoclass('com.local.ifunnyshare.ShareActivity'))
            intent.setAction(Intent.ACTION_SEND)
            intent.setType('text/plain')
            intent.putExtra(Intent.EXTRA_TEXT, str(text))
            mActivity.getIntent().removeExtra(Intent.EXTRA_TEXT)
            mActivity.startActivity(intent)
            mActivity.finish()
            return
        prefs = mActivity.getSharedPreferences('download_results', 0)
        last = prefs.getString('last_result', '')
        if last:
            self.label.text += '\n\nLast result:\n' + str(last)
        Version = autoclass('android.os.Build$VERSION')
        if Version.SDK_INT >= 33:
            request_permissions(['android.permission.POST_NOTIFICATIONS'])


IFunnySetupApp().run()
