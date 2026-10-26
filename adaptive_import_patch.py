#!/usr/bin/env python3
"""Apply adaptive concurrency ceiling to the existing Komikku importer."""
from pathlib import Path
root = Path("/tmp/komikku-source-import/komikku-master")
main = root / "app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/browse"
test = root / "app/src/test/java/eu/kanade/tachiyomi/ui/browse/source/browse"
changes = {
    main / "SourceImportAutoTuner.kt": [("private val maximum: Int = 32", "private val maximum: Int = 64")],
    main / "SourceImportLoader.kt": [("preloadPages.coerceIn(1, 32)", "preloadPages.coerceIn(1, 64)")],
    main / "BrowseSourceScreenModel.kt": [
        ("preloadPages = if (sourceImportAutomatic) 32 else sourceImportPreloadPages.coerceIn(1, 32)", "preloadPages = if (sourceImportAutomatic) 64 else sourceImportPreloadPages.coerceIn(1, 64)"),
    ],
    main / "BrowseSourceScreen.kt": [
        ("sourceImportPreloadPages.coerceIn(1, 32)", "sourceImportPreloadPages.coerceIn(1, 64)"),
        ("valueRange = 1f..32f", "valueRange = 1f..64f"),
        ("steps = 30,", "steps = 62,"),
    ],
    main / "SourceImportJournal.kt": [
        ("val preloadPages: Int = 32", "val preloadPages: Int = 64"),
        ("preloadPages\").toInt().coerceIn(1, 32)", "preloadPages\").toInt().coerceIn(1, 64)"),
    ],
    test / "SourceImportAutoTunerTest.kt": [
        ("listOf(1, 2, 4, 8, 16, 32)", "listOf(1, 2, 4, 8, 16, 32, 64)"),
        ("assertEquals(32, tuner.limit)", "assertEquals(64, tuner.limit)"),
    ],
    test / "SourceImportAutoLoaderTest.kt": [
        ("SourceImportTelemetry(32,", "SourceImportTelemetry(64,"),
        (" }, 32,", " }, 64,"),
        ("assertEquals(32, maximum)", "assertEquals(64, maximum)"),
        ("assertEquals(32, stats.snapshot().effectiveConcurrency)", "assertEquals(64, stats.snapshot().effectiveConcurrency)"),
    ],
}
for path, replacements in changes.items():
    data = path.read_text()
    for before, after in replacements:
        if before not in data:
            raise RuntimeError(f"Expected text absent in {path}: {before}")
        data = data.replace(before, after)
    path.write_text(data)
    print(f"Patched {path.relative_to(root)}")
print("Adaptive pagination mode: automatic tuning 16-64; manual override 1-64.")
