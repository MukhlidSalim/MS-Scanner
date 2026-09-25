import re

with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

# Add selection state variables
state_vars = """
    var showTrashDialog by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedDocIds by remember { mutableStateOf(setOf<Long>()) }
"""
content = content.replace("    var showTrashDialog by remember { mutableStateOf(false) }", state_vars.strip('\n'))

# Add top bar actions for selection mode
top_bar_original = """        topBar = {
            TopAppBar(
                title = {"""

top_bar_new = """        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (selectionMode) {
                        IconButton(onClick = { selectionMode = false; selectedDocIds = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                        }
                    }
                },
                title = {"""
                
content = content.replace(top_bar_original, top_bar_new)

# Title logic
title_original = """                        Text(
                            text = "DocScan Pro",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )"""
title_new = """                        Text(
                            text = if (selectionMode) "${selectedDocIds.size} Selected" else "DocScan Pro",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )"""
content = content.replace(title_original, title_new)

# Actions logic
actions_original = """                actions = {
                    IconButton(
                        onClick = { isGridView = !isGridView },
                        modifier = Modifier.testTag("toggle_view_btn")
                    ) {"""
actions_new = """                actions = {
                    if (selectionMode) {
                        IconButton(onClick = {
                            selectedDocIds.forEach { viewModel.moveToTrash(it) }
                            selectionMode = false
                            selectedDocIds = emptySet()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Selected", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                    IconButton(
                        onClick = { isGridView = !isGridView },
                        modifier = Modifier.testTag("toggle_view_btn")
                    ) {"""
content = content.replace(actions_original, actions_new)

# Close actions else
settings_original = """                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("settings_btn")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.desc_settings))
                    }
                },"""
settings_new = """                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.testTag("settings_btn")
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.desc_settings))
                    }
                    }
                },"""
content = content.replace(settings_original, settings_new)

# Replace LazyVerticalGrid item
grid_original = """                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.documents) { doc ->
                        com.example.ui.components.DocumentCard(
                            document = doc,
                            onClick = { onNavigateToDocument(doc.id) },"""

grid_new = """                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.documents) { doc ->
                        com.example.ui.components.DocumentCard(
                            document = doc,
                            isSelected = selectedDocIds.contains(doc.id),
                            onClick = { 
                                if (selectionMode) {
                                    selectedDocIds = if (selectedDocIds.contains(doc.id)) selectedDocIds - doc.id else selectedDocIds + doc.id
                                    if (selectedDocIds.isEmpty()) selectionMode = false
                                } else {
                                    onNavigateToDocument(doc.id) 
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedDocIds = setOf(doc.id)
                                }
                            },"""

content = content.replace(grid_original, grid_new)

# Replace LazyColumn item
list_original = """                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.documents) { doc ->
                        com.example.ui.components.DocumentCard(
                            document = doc,
                            onClick = { onNavigateToDocument(doc.id) },"""

list_new = """                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.documents) { doc ->
                        com.example.ui.components.DocumentCard(
                            document = doc,
                            isSelected = selectedDocIds.contains(doc.id),
                            onClick = { 
                                if (selectionMode) {
                                    selectedDocIds = if (selectedDocIds.contains(doc.id)) selectedDocIds - doc.id else selectedDocIds + doc.id
                                    if (selectedDocIds.isEmpty()) selectionMode = false
                                } else {
                                    onNavigateToDocument(doc.id) 
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectionMode = true
                                    selectedDocIds = setOf(doc.id)
                                }
                            },"""
                            
content = content.replace(list_original, list_new)

with open("app/src/main/java/com/example/ui/screens/home/HomeScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
