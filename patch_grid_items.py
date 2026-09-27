import os

filepath = 'app/src/main/java/com/example/ui/screens/home/components/HomeGridItems.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

content = content.replace('    onLongClick: () -> Unit\n) {', '    onLongClick: () -> Unit,\n    onDeleteClick: () -> Unit = {}\n) {')
content = content.replace('onClick = { expanded = false } // TODO: Implement\n                            )\n                        }\n                    }', 'onClick = { expanded = false; onDeleteClick() }\n                            )\n                        }\n                    }')

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
