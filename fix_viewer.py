import re
with open("app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

bottom_actions_start = """} // End of HorizontalPager else branch
            // Bottom Actions"""

bottom_actions_new = """} // End of HorizontalPager else branch
            // Bottom Actions
            if (!isGridView) {"""

content = content.replace(bottom_actions_start, bottom_actions_new)

bottom_actions_end = """            }
        }
    }

    // PDF Export & Share Dialog"""

bottom_actions_end_new = """            }
            } // End of if(!isGridView)
        }
    }

    // PDF Export & Share Dialog"""

content = content.replace(bottom_actions_end, bottom_actions_end_new)

back_handler = """    BackHandler {
        onNavigateBack()
    }"""

back_handler_new = """    BackHandler {
        if (!isGridView) {
            isGridView = true
        } else {
            onNavigateBack()
        }
    }"""

content = content.replace(back_handler, back_handler_new)

back_btn_old = """                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },"""

back_btn_new = """                navigationIcon = {
                    IconButton(onClick = {
                        if (!isGridView) {
                            isGridView = true
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.desc_back))
                    }
                },"""

content = content.replace(back_btn_old, back_btn_new)

with open("app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
