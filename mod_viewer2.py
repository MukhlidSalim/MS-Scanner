import re

with open("app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt", "r", encoding="utf-8") as f:
    content = f.read()

pattern = r"""                        itemsIndexed\(pages\) \{ index, p ->
                            val isSelected = index == pagerState\.currentPage
                            Box\(
                                modifier = Modifier
                                    \.size\(44\.dp, 60\.dp\)
                                    \.clip\(RoundedCornerShape\(6\.dp\)\)
                                    \.border\(
                                        width = if \(isSelected\) 2\.5\.dp else 1\.dp,
                                        color = if \(isSelected\) EmeraldLight else MaterialTheme\.colorScheme\.outline,
                                        shape = RoundedCornerShape\(6\.dp\)
                                    \)
                                    \.clickable \{
                                        // Scroll to selected page
                                    \}
                            \) \{
                                AsyncImage\(
                                    model = File\(p\.processedImagePath\),
                                    contentDescription = null,
                                    contentScale = ContentScale\.Crop,
                                    modifier = Modifier\.fillMaxSize\(\)
                                \)
                            \}"""

replacement = """                        itemsIndexed(pages) { index, p ->
                            val isCurrentPage = index == pagerState.currentPage
                            val isPageSelected = selectedPageIds.contains(p.id)
                            Box(
                                modifier = Modifier
                                    .size(44.dp, 60.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(
                                        width = if (isCurrentPage) 2.5.dp else 1.dp,
                                        color = if (isCurrentPage) EmeraldLight else MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable { 
                                        if (selectionMode) {
                                            selectedPageIds = if (isPageSelected) selectedPageIds - p.id else selectedPageIds + p.id
                                        } else {
                                            coroutineScope.launch { pagerState.animateScrollToPage(index) } 
                                        }
                                    }
                            ) {
                                AsyncImage(
                                    model = File(p.processedImagePath),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (selectionMode) {
                                    Box(
                                        modifier = Modifier
                                            .padding(4.dp)
                                            .size(16.dp)
                                            .align(Alignment.TopEnd)
                                            .clip(CircleShape)
                                            .background(if (isPageSelected) EmeraldLight else Color.Black.copy(alpha = 0.4f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isPageSelected) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                        }
                                    }
                                }
                            }"""

content = re.sub(pattern, replacement, content)

with open("app/src/main/java/com/example/ui/screens/viewer/DocumentViewerScreen.kt", "w", encoding="utf-8") as f:
    f.write(content)
