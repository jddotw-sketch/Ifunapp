# Komikku Source Import

This branch contains the updated Komikku project archive in seven binary parts. Join the parts in order to restore the ZIP:

```sh
cat komikku-source-import.zip.part-* > komikku-source-import.zip
unzip komikku-source-import.zip
```

The archive SHA-256 is `$(sha256sum komikku-source-import.zip | cut -d' ' -f1)`.
