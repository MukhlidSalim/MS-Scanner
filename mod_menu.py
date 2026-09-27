import re

with open('app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

pattern = r'DropdownMenuItem\(\s*text = \{ Text\(stringResource\(R\.string\.txt_move_left\)\) \},.*?DropdownMenuItem\(\s*text = \{ Text\(stringResource\(R\.string\.txt_move_right\)\) \},.*?leadingIcon = \{ Icon\(Icons\.Default\.ArrowForwardIos, contentDescription = null\) \}\s*\)'

replacement = """DropdownMenuItem(
                                    text = { Text(stringResource(R.string.txt_sort)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showReorderScreen = true
                                    },
                                    leadingIcon = { Icon(Icons.Default.Reorder, contentDescription = null) }
                                )"""

new_content = re.sub(pattern, replacement, content, flags=re.DOTALL)

with open('app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt', 'w', encoding='utf-8') as f:
    f.write(new_content)
