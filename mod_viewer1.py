import re

with open("app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

# Add selection state variables
state_vars_pattern = r"""    val pagerState = rememberPagerState\(pageCount = \{ pages\.size \}\)"""
state_vars_replacement = """    val pagerState = rememberPagerState(pageCount = { pages.size })
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPageIds by remember { mutableStateOf(setOf<Long>()) }"""
content = re.sub(state_vars_pattern, state_vars_replacement, content)

# Top Bar
top_bar_pattern = r"""                title = \{
                    var showRenameDialog by remember \{ mutableStateOf\(false\) \}
                    var renameInput by remember \{ mutableStateOf\(doc\?\.title \?: ""\) \}

                    Column\(modifier = Modifier\.clickable \{ 
                        renameInput = doc\?\.title \?: ""
                        showRenameDialog = true 
                    \}\) \{
                        Text\(
                            text = doc\?\.title \?: "Document",
                            style = MaterialTheme\.typography\.titleMedium,
                            fontWeight = FontWeight\.Bold,
                            maxLines = 1
                        \)
                        if \(pages\.isNotEmpty\(\)\) \{
                            Text\(
                                text = "Page \$\{pagerState\.currentPage \+ 1\} of \$\{pages\.size\}",
                                style = MaterialTheme\.typography\.bodySmall,
                                color = MaterialTheme\.colorScheme\.onSurfaceVariant
                            \)
                        \}
                    \}"""

top_bar_replacement = """                title = {
                    if (selectionMode) {
                        Text("${selectedPageIds.size} Selected", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    } else {
                        var showRenameDialog by remember { mutableStateOf(false) }
                        var renameInput by remember { mutableStateOf(doc?.title ?: "") }

                        Column(modifier = Modifier.clickable { 
                            renameInput = doc?.title ?: ""
                            showRenameDialog = true 
                        }) {
                            Text(
                                text = doc?.title ?: "Document",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            if (pages.isNotEmpty()) {
                                Text(
                                    text = "Page ${pagerState.currentPage + 1} of ${pages.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    """
content = content.replace(re.search(top_bar_pattern, content).group(0), top_bar_replacement)

# Navigation icon
nav_icon_pattern = r"""                navigationIcon = \{
                    IconButton\(onClick = onNavigateBack\) \{
                        Icon\(Icons\.Default\.ArrowBack, contentDescription = stringResource\(R\.string\.desc_back\)\)
                    \}
                \},"""
nav_icon_replacement = """                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { selectionMode = false; selectedPageIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                        }
                    } else {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                        }
                    }
                },"""
content = re.sub(nav_icon_pattern, nav_icon_replacement, content)

# Actions
actions_pattern = r"""                actions = \{
                    var showShareMenu by remember \{ mutableStateOf\(false\) \}

                    IconButton\(onClick = \{ showShareMenu = true \}\) \{
                        Icon\(Icons\.Default\.Share, contentDescription = stringResource\(R\.string\.desc_share_menu\)\)
                    \}

                    DropdownMenu\(
                        expanded = showShareMenu,
                        onDismissRequest = \{ showShareMenu = false \}
                    \) \{
                        DropdownMenuItem\(
                            text = \{ Text\(stringResource\(R\.string\.txt_share_all_jpegs\)\) \},
                            onClick = \{
                                showShareMenu = false
                                val allFiles = pages\.map \{ File\(it\.processedImagePath\) \}
                                shareMultipleFiles\(context, allFiles\)
                            \}
                        \)
                        DropdownMenuItem\(
                            text = \{ Text\(stringResource\(R\.string\.txt_share_current_page_jpeg\)\) \},
                            onClick = \{
                                showShareMenu = false
                                if \(activePage != null\) \{
                                    shareFile\(context, File\(activePage\.processedImagePath\)\)
                                \}
                            \}
                        \)
                    \}

                    IconButton\(
                        onClick = \{ showPdfDialog = true \},
                        modifier = Modifier\.testTag\("export_pdf_btn"\)
                    \) \{
                        Icon\(Icons\.Default\.PictureAsPdf, contentDescription = stringResource\(R\.string\.desc_export_pdf\), tint = MaterialTheme\.colorScheme\.primary\)
                    \}
                \}"""

actions_replacement = """                actions = {
                    if (selectionMode) {
                        IconButton(onClick = {
                            val selectedFiles = pages.filter { selectedPageIds.contains(it.id) }.map { File(it.processedImagePath) }
                            if (selectedFiles.isNotEmpty()) shareMultipleFiles(context, selectedFiles)
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share Selected")
                        }
                        IconButton(onClick = {
                            if (selectedPageIds.isNotEmpty()) showPdfDialog = true
                        }) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = "Export Selected as PDF")
                        }
                    } else {
                        var showShareMenu by remember { mutableStateOf(false) }

                        IconButton(onClick = { showShareMenu = true }) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.desc_share_menu))
                        }

                        DropdownMenu(
                            expanded = showShareMenu,
                            onDismissRequest = { showShareMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Select Pages") },
                                onClick = {
                                    showShareMenu = false
                                    selectionMode = true
                                    selectedPageIds = setOf(activePage?.id ?: pages.firstOrNull()?.id ?: 0L).filter { it != 0L }.toSet()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_share_all_jpegs)) },
                                onClick = {
                                    showShareMenu = false
                                    val allFiles = pages.map { File(it.processedImagePath) }
                                    shareMultipleFiles(context, allFiles)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.txt_share_current_page_jpeg)) },
                                onClick = {
                                    showShareMenu = false
                                    if (activePage != null) {
                                        shareFile(context, File(activePage.processedImagePath))
                                    }
                                }
                            )
                        }

                        IconButton(
                            onClick = { showPdfDialog = true },
                            modifier = Modifier.testTag("export_pdf_btn")
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = stringResource(R.string.desc_export_pdf), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }"""
content = re.sub(actions_pattern, actions_replacement, content)

with open("app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
