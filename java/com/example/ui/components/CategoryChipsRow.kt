package com.example.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.*
import com.example.data.model.DocumentCategory
import java.util.Locale

@Composable
fun CategoryChipsRow(
    selectedCategory: DocumentCategory,
    onCategorySelected: (DocumentCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DocumentCategory.values().forEach { cat ->
            val isSelected = cat == selectedCategory
            val isArabic = Locale.getDefault().language == "ar"
            val displayName = if (isArabic) cat.displayNameAr else cat.displayNameEn
            FilterChip(
                selected = isSelected,
                onClick = { onCategorySelected(cat) },
                shape = RoundedCornerShape(50),
                label = {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor      = Color.Transparent,
                    labelColor          = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = Color.Transparent, // No fill even when selected
                    selectedLabelColor  = GoldBase
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled  = true,
                    selected = isSelected,
                    borderColor         = MaterialTheme.colorScheme.outline,
                    selectedBorderColor = GoldBase,
                    borderWidth         = 0.75.dp,
                    selectedBorderWidth = 1.5.dp
                ),
                elevation = FilterChipDefaults.filterChipElevation(elevation = 0.dp)
            )
        }
    }
}
