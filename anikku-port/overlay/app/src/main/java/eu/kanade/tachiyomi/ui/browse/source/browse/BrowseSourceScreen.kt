package eu.kanade.tachiyomi.ui.browse.source.browse
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import kotlin.math.roundToInt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.browse.components.BulkFavoriteDialogs
import eu.kanade.presentation.browse.components.RemoveMangaDialog
import eu.kanade.presentation.browse.components.SavedSearchCreateDialog
import eu.kanade.presentation.browse.components.SavedSearchDeleteDialog
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.BulkSelectionToolbar
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.BulkFavoriteScreenModel
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.migration.season.MigrateSeasonSelectScreen
import eu.kanade.tachiyomi.ui.browse.source.SourcesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel.Listing
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import eu.kanade.tachiyomi.util.system.toast
import exh.source.anyIs
import exh.ui.smartsearch.SmartSearchScreen
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.feature.migration.dialog.MigrateMangaDialog
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

data class BrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
    // SY -->
    private val filtersJson: String? = null,
    private val savedSearch: Long? = null,
    /** being set when called from [SmartSearchScreen] or when click on a manga from this screen
     * which was previously opened from `SmartSearchScreen` */
    private val smartSearchConfig: SourcesScreen.SmartSearchConfig? = null,
    // SY <--
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val screenModel = rememberScreenModel {
            BrowseSourceScreenModel(
                sourceId = sourceId,
                listingQuery = listingQuery,
                // SY -->
                filtersJson = filtersJson,
                savedSearch = savedSearch,
                // SY <--
            )
        }
        val state by screenModel.state.collectAsState()

        val navigator = LocalNavigator.currentOrThrow
        val navigateUp: () -> Unit = {
            when {
                !state.isUserQuery && state.toolbarQuery != null -> screenModel.setToolbarQuery(null)
                else -> navigator.pop()
            }
        }

        // SY -->
        val context = LocalContext.current
        // SY <--

        // KMK -->
        screenModel.source.let {
            // KMK <--
            if (it is StubSource) {
                MissingSourceScreen(
                    source = it,
                    navigateUp = navigateUp,
                )
                return
            }
        }

        val scope = rememberCoroutineScope()
        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }
        var showImportInfo by remember { mutableStateOf(false) }

        val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }
        val onWebViewClick = f@{
            val source = screenModel.source as? HttpSource ?: return@f
            navigator.push(
                WebViewScreen(
                    url = source.baseUrl,
                    initialTitle = source.name,
                    sourceId = source.id,
                ),
            )
        }

        // KMK -->
        val bulkFavoriteScreenModel = rememberScreenModel { BulkFavoriteScreenModel() }
        val bulkFavoriteState by bulkFavoriteScreenModel.state.collectAsState()

        BackHandler(enabled = bulkFavoriteState.selectionMode) {
            bulkFavoriteScreenModel.backHandler()
        }
        // KMK <--

        LaunchedEffect(screenModel.source) {
            assistUrl = (screenModel.source as? HttpSource)?.baseUrl
        }

        // KMK -->
        val mangaList = screenModel.mangaPagerFlowFlow.collectAsLazyPagingItems()

        val isConfigurableSource = screenModel.source.anyIs<ConfigurableSource>()
        // KMK <--

        // AY -->
        var topBarHeight by remember { mutableIntStateOf(0) }
        // <-- AY
        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {}
                        // AY -->
                        .onSizeChanged { topBarHeight = it.height },
                    // <-- AY
                ) {
                    // KMK -->
                    if (bulkFavoriteState.selectionMode) {
                        BulkSelectionToolbar(
                            selectedCount = bulkFavoriteState.selection.size,
                            isRunning = bulkFavoriteState.isRunning,
                            onClickClearSelection = bulkFavoriteScreenModel::toggleSelectionMode,
                            onChangeCategoryClick = bulkFavoriteScreenModel::addFavorite,
                            onSelectAll = {
                                mangaList.itemSnapshotList.items
                                    .map { it.value.first }
                                    .forEach { bulkFavoriteScreenModel.select(it) }
                            },
                            onReverseSelection = {
                                mangaList.itemSnapshotList.items
                                    .map { it.value.first }
                                    .let { bulkFavoriteScreenModel.reverseSelection(it) }
                            },
                        )
                    } else {
                        // KMK <--
                        BrowseSourceToolbar(
                            searchQuery = state.toolbarQuery,
                            onSearchQueryChange = screenModel::setToolbarQuery,
                            source = screenModel.source,
                            displayMode = screenModel.displayMode,
                            onDisplayModeChange = { screenModel.displayMode = it },
                            navigateUp = navigateUp,
                            onWebViewClick = onWebViewClick,
                            onHelpClick = onHelpClick,
                            onImportAll = screenModel::requestImportAllFromListing,
                            // KMK -->
                            onToggleIncognito = screenModel::toggleIncognitoMode,
                            onSettingsClick = {
                                navigator.push(SourcePreferencesScreen(sourceId))
                            }.takeIf { isConfigurableSource },
                            // KMK <--
                            onSearch = screenModel::search,
                            // KMK -->
                            toggleSelectionMode = bulkFavoriteScreenModel::toggleSelectionMode,
                            isRunning = bulkFavoriteState.isRunning,
                            // KMK <--
                        )
                    }

                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = MaterialTheme.padding.small),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        FilterChip(
                            selected = state.listing == Listing.Popular,
                            onClick = {
                                screenModel.resetFilters()
                                screenModel.setListing(Listing.Popular)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(FilterChipDefaults.IconSize),
                                )
                            },
                            label = {
                                Text(text = stringResource(MR.strings.popular))
                            },
                        )
                        if ((screenModel.source as CatalogueSource).supportsLatest) {
                            FilterChip(
                                selected = state.listing == Listing.Latest,
                                onClick = {
                                    screenModel.resetFilters()
                                    screenModel.setListing(Listing.Latest)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.NewReleases,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.latest))
                                },
                            )
                        }
                        if (/* SY --> */ state.filterable /* SY <-- */) {
                            FilterChip(
                                selected = state.listing is Listing.Search &&
                                    // KMK -->
                                    (state.listing as Listing.Search).savedSearchId == null,
                                // KMK <--
                                onClick = screenModel::openFilterSheet,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.FilterList,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    // SY -->
                                    Text(
                                        text = if (state.filters.isNotEmpty()) {
                                            stringResource(MR.strings.action_filter)
                                        } else {
                                            stringResource(MR.strings.action_search)
                                        },
                                    )
                                    // SY <--
                                },
                            )
                        }
                        // KMK -->
                        state.savedSearches.forEach { savedSearch ->
                            FilterChip(
                                selected = state.listing is Listing.Search &&
                                    (state.listing as Listing.Search).savedSearchId == savedSearch.id,
                                onClick = {
                                    screenModel.onSavedSearch(savedSearch) {
                                        context.toast(it)
                                    }
                                },
                                label = {
                                    Text(
                                        text = savedSearch.name,
                                    )
                                },
                            )
                        }
                        // KMK <--
                    }

                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { paddingValues ->
            BrowseSourceContent(
                source = screenModel.source,
                mangaList = mangaList,
                columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                // AY -->
                entries = screenModel.getColumnsPreferenceForCurrentOrientation(LocalConfiguration.current.orientation),
                topBarHeight = topBarHeight,
                // <-- AY
                displayMode = screenModel.displayMode,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = onWebViewClick,
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = onHelpClick,
                onMangaClick = { manga ->
                    // KMK -->
                    if (bulkFavoriteState.selectionMode) {
                        bulkFavoriteScreenModel.toggleSelection(manga)
                    } else {
                        // KMK <--
                        navigator.push(
                            MangaScreen(
                                mangaId = manga.id,
                                // KMK -->
                                // Finding the entry to be merged to, so we don't want to expand description
                                // so that user can see the `Merge to another` button
                                fromSource = smartSearchConfig == null,
                                // KMK <--
                                smartSearchConfig = smartSearchConfig,
                            ),
                        )
                    }
                },
                onMangaLongClick = { manga ->
                    // KMK -->
                    if (bulkFavoriteState.selectionMode) {
                        navigator.push(MangaScreen(manga.id, true))
                    } else {
                        // KMK <--
                        scope.launchIO {
                            val duplicates = screenModel.getDuplicateLibraryManga(manga)
                            when {
                                manga.favorite -> screenModel.setDialog(BrowseSourceScreenModel.Dialog.RemoveManga(manga))
                                duplicates.isNotEmpty() -> screenModel.setDialog(
                                    BrowseSourceScreenModel.Dialog.AddDuplicateManga(manga, duplicates),
                                )
                                else -> screenModel.addFavorite(manga)
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                },
                // KMK -->
                selection = bulkFavoriteState.selection,
                // KMK <--
            )
        }

        val onDismissRequest = { screenModel.setDialog(null) }
        when (val dialog = state.dialog) {
            BrowseSourceScreenModel.Dialog.ConfirmImportAll -> {
                // KMK -->
                var slowMode by remember { mutableStateOf(false) }
                // KMK <--
                AlertDialog(
                    onDismissRequest = onDismissRequest,
                    title = { Text(stringResource(KMR.strings.action_add_all_from_listing)) },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                stringResource(
                                    KMR.strings.source_import_all_confirmation,
                                    screenModel.source.name,
                                ),
                            )
                            if (state.sourceImportHasCheckpoint) {
                                Text(stringResource(KMR.strings.source_import_checkpoint, state.sourceImportSavedPages))
                                Text(
                                    stringResource(KMR.strings.source_import_resume_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (state.sourceImportCanResume && !state.sourceImportRestartRequested) {
                                    TextButton(onClick = { screenModel.importAllFromListing(slowMode) }) {
                                        Text(stringResource(KMR.strings.source_import_restart))
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = screenModel.sourceImportMinimumPages,
                                onValueChange = { value ->
                                    screenModel.sourceImportMinimumPages = value.filter(Char::isDigit).take(6)
                                },
                                label = { Text("Minimum expected pages (0 = automatic)") },
                                supportingText = { Text("Enter 2188 for Pururin to detect premature termination") },
                                singleLine = true,
                            )
                            Text("Import mode", style = MaterialTheme.typography.titleSmall)
                            listOf(
                                "STREAM" to "Stream & Save — original importer",
                                "BUFFERED" to "Buffered — download ahead, save in parallel",
                                "DOWNLOAD_ALL" to "Download All → Save — cache full catalog first",
                                "UNBOUNDED" to "Uncapped Lookahead — disk-backed loader without saver cap",
                            ).forEach { (value, label) ->
                                FilterChip(
                                    selected = screenModel.sourceImportMode == value,
                                    onClick = { screenModel.sourceImportMode = value },
                                    label = { Text(label) },
                                )
                            }
                            Text(
                                "Disk-backed modes require available internal storage. Concurrent requests remain limited to 64.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            // KMK -->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(KMR.strings.source_import_slow_mode))
                                    Text(
                                        stringResource(KMR.strings.source_import_slow_mode_summary),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(
                                    checked = slowMode,
                                    onCheckedChange = { slowMode = it },
                                )
                            }
                            // KMK <--
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    stringResource(KMR.strings.source_import_auto),
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = screenModel.sourceImportAutomatic,
                                    onCheckedChange = { screenModel.sourceImportAutomatic = it },
                                )
                            }
                            Text(
                                stringResource(KMR.strings.source_import_auto_summary),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (!screenModel.sourceImportAutomatic) {
                                Text(stringResource(KMR.strings.source_import_preload_value, screenModel.sourceImportPreloadPages.coerceIn(1, 64)))
                                Slider(
                                    value = screenModel.sourceImportPreloadPages.coerceIn(1, 64).toFloat(),
                                    onValueChange = { screenModel.sourceImportPreloadPages = it.roundToInt().coerceIn(1, 64) },
                                    valueRange = 1f..64f,
                                    steps = 62,
                                )
                                Text(
                                    stringResource(KMR.strings.source_import_preload_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (state.sourceImportCanResume && !state.sourceImportRestartRequested) screenModel.resumeSourceImport() else screenModel.importAllFromListing(slowMode)
                            },
                        ) {
                            Text(
                                stringResource(
                                    when {
                                        state.sourceImportCanResume && !state.sourceImportRestartRequested -> KMR.strings.source_import_resume
                                        state.sourceImportHasCheckpoint -> KMR.strings.source_import_restart
                                        else -> MR.strings.action_add
                                    },
                                ),
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismissRequest) {
                            Text(stringResource(MR.strings.action_cancel))
                        }
                    },
                )
            }
            // KMK <--
            is BrowseSourceScreenModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = screenModel::resetFilters,
                    onFilter = { screenModel.search(filters = state.filters) },
                    onUpdate = screenModel::setFilters,
                    // SY -->
                    startExpanded = screenModel.startExpanded,
                    onSave = screenModel::onSaveSearch,
                    savedSearches = state.savedSearches,
                    onSavedSearch = { search ->
                        screenModel.onSavedSearch(search) {
                            context.toast(it)
                        }
                    },
                    onSavedSearchPress = screenModel::onSavedSearchPress,
                    // SY <--
                    // KMK -->
                    onSavedSearchPressDesc = stringResource(KMR.strings.saved_searches_delete),
                    // KMK <--
                )
            }
            is BrowseSourceScreenModel.Dialog.AddDuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { screenModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = { screenModel.setDialog(BrowseSourceScreenModel.Dialog.Migrate(dialog.manga, it)) },
                    // KMK -->
                    targetManga = dialog.manga,
                    // KMK <--
                )
            }

            is BrowseSourceScreenModel.Dialog.Migrate -> {
                MigrateMangaDialog(
                    current = dialog.current,
                    target = dialog.target,
                    // Initiated from the context of [dialog.target] so we show [dialog.current].
                    onClickTitle = { navigator.push(MangaScreen(dialog.current.id)) },
                    // AY -->
                    onClickSeasons = { navigator.push(MigrateSeasonSelectScreen(dialog.current, dialog.target)) },
                    // <-- AY
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceScreenModel.Dialog.RemoveManga -> {
                RemoveMangaDialog(
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        screenModel.changeMangaFavorite(dialog.manga)
                    },
                    mangaToRemove = dialog.manga,
                )
            }
            is BrowseSourceScreenModel.Dialog.ChangeMangaCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        screenModel.changeMangaFavorite(dialog.manga)
                        screenModel.moveMangaToCategories(dialog.manga, include)
                    },
                )
            }
            is BrowseSourceScreenModel.Dialog.CreateSavedSearch -> SavedSearchCreateDialog(
                onDismissRequest = onDismissRequest,
                currentSavedSearches = dialog.currentSavedSearches,
                saveSearch = screenModel::saveSearch,
            )
            is BrowseSourceScreenModel.Dialog.DeleteSavedSearch -> SavedSearchDeleteDialog(
                onDismissRequest = onDismissRequest,
                name = dialog.name,
                deleteSavedSearch = {
                    screenModel.deleteSearch(dialog.idToDelete)
                },
            )
            else -> {}
        }

        // KMK -->
        state.sourceImportProgress?.takeUnless { showImportInfo || !state.sourceImportShowProgress }?.let { count ->
            AlertDialog(
                onDismissRequest = screenModel::hideSourceImportProgress,
                title = { Text(screenModel.source.name) },
                text = {
                    Column {
                        Text(stringResource(KMR.strings.source_import_all_progress, count))
                        Text(stringResource(KMR.strings.source_import_existing, state.sourceImportExistingCount))
                        Text("Pages downloaded: ${state.sourceImportDownloadedPages}")
                        Text("Pages saved: ${state.sourceImportSavedPages}")
                        Text(stringResource(KMR.strings.source_import_background_summary))
                        state.sourceImportRetryDelayMillis?.let { retryDelay ->
                            Text(stringResource(KMR.strings.source_import_retrying, retryDelay / 1_000))
                        }
                        state.sourceImportStats?.lastSlowdown?.let { issue ->
                            Text("Last slowdown · page ${issue.pageKey ?: "?"}: ${issue.cause}")
                        }
                    }
                },
                confirmButton = {
                    Column {
                        if (state.sourceImportModeRunning == "DOWNLOAD_ALL") {
                            TextButton(
                                onClick = screenModel::saveDownloadedSourceImport,
                                enabled = !state.sourceImportSaveRequested && state.sourceImportDownloadedPages > state.sourceImportSavedPages,
                            ) {
                                Text(if (state.sourceImportSaveRequested) "Saving requested…" else "Save Downloaded Pages")
                            }
                        }
                        TextButton(onClick = screenModel::stopSourceImport) {
                        Text(stringResource(KMR.strings.source_import_pause))
                        }
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { showImportInfo = true }, enabled = state.sourceImportStats != null) {
                            Text(stringResource(KMR.strings.source_import_info))
                        }
                        TextButton(onClick = screenModel::hideSourceImportProgress) {
                            Text(stringResource(KMR.strings.source_import_hide))
                        }
                    }
                },
            )
        }
        state.sourceImportResult?.takeUnless { showImportInfo }?.let { result ->
            AlertDialog(
                onDismissRequest = screenModel::dismissSourceImportResult,
                title = { Text(screenModel.source.name) },
                text = {
                    Column {
                        Text(
                            when {
                                result.stopped -> stringResource(KMR.strings.source_import_paused, result.addedCount)
                                result.error != null -> stringResource(
                                    KMR.strings.source_import_all_failed,
                                    result.addedCount,
                                    result.error,
                                )
                                else -> stringResource(
                                    KMR.strings.source_import_all_complete,
                                    result.addedCount,
                                    screenModel.source.name,
                                )
                            },
                        )
                        Text(stringResource(KMR.strings.source_import_existing, result.existingCount))
                        if (state.sourceImportHasCheckpoint) {
                            Text(stringResource(KMR.strings.source_import_resume_summary))
                            Row {
                                if (state.sourceImportCanResume) {
                                    TextButton(onClick = screenModel::resumeSourceImport) {
                                        Text(stringResource(KMR.strings.source_import_resume))
                                    }
                                }
                                TextButton(onClick = screenModel::requestRestartSourceImport) {
                                    Text(stringResource(KMR.strings.source_import_restart))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = screenModel::dismissSourceImportResult) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showImportInfo = true }, enabled = state.sourceImportStats != null) {
                        Text(stringResource(KMR.strings.source_import_info))
                    }
                },
            )
        }
        if (showImportInfo) {
            state.sourceImportStats?.let { stats ->
                SourceImportInfoSheet(
                    sourceName = screenModel.source.name,
                    stats = stats,
                    onDismiss = { showImportInfo = false },
                    onStop = if (state.sourceImportProgress != null) screenModel::stopSourceImport else null,
                )
            }
        }
        // KMK <--

        // Bulk-favorite actions only
        BulkFavoriteDialogs(
            bulkFavoriteScreenModel = bulkFavoriteScreenModel,
            dialog = bulkFavoriteState.dialog,
        )
        // KMK <--

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> screenModel.searchGenre(it.txt)
                        is SearchType.Text -> screenModel.search(it.txt)
                    }
                }
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}
