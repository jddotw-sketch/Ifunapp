"""Python worker in the separate foreground-service process, with no Kivy UI."""
import traceback
from jnius import autoclass
from downloader import extract_ifunny_url, fetch_post
from storage import save_to_downloads


def run(service, classes=autoclass):
    while True:
        text = service.nextJob()
        try:
            url = extract_ifunny_url(str(text))
            service.updateProgress('Downloading…')
            data, filename, mime = fetch_post(url, crop_logo=True)
            uri = save_to_downloads(data, filename, mime, service.getApplicationContext(),
                                    classes, service.updateProgress)
            service.reportSuccess(uri, filename, mime)
        except Exception as error:
            traceback.print_exc()
            service.reportFailure(f'{type(error).__name__}: {error}')
        finally:
            service.jobDone()


if __name__ == '__main__':
    run(autoclass('com.local.ifunnyshare.DownloadServiceBase').current())
