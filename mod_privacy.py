import re

with open("app/src/main/java/com/example/ui/screens/settings/SettingsScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

pattern = r"""                    \}
                \}
            \}

            // Storage Management"""

replacement = """                    }
                }
            }

            // Legal & Info
            Text(
                text = "Legal",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com/privacy"))
                            context.startActivity(intent)
                        }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Privacy Policy", fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }

            // Storage Management"""

content = re.sub(pattern, replacement, content)

with open("app/src/main/java/com/example/ui/screens/settings/SettingsScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
