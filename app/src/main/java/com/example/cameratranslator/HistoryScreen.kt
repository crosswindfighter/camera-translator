package com.example.cameratranslator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cameratranslator.ui.theme.SectionCardStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bu oturumda çevrilen altyazılar (İngilizcesi ve çevirisi). Yalnızca bellekte tutulur:
 * uygulama kapanınca silinir, diske yazılmaz.
 */
object TranslationHistory {
    data class Entry(val id: Long, val time: Long, val source: String, val translation: String, val area: Int)

    private const val MAX_ENTRIES = 500
    private var nextId = 0L
    val entries = mutableStateListOf<Entry>()

    /** [area]: 1 = altyazı alanı, 2–3 = ekran çevirisinin ek alanları */
    fun add(source: String, translation: String, area: Int = 1) {
        if (source.isBlank() || translation.isBlank()) return
        if (entries.size >= MAX_ENTRIES) entries.removeAt(0)
        entries.add(Entry(nextId++, System.currentTimeMillis(), source, translation, area))
    }

    fun clear() = entries.clear()
}

/** Çeviri geçmişi: kaçırılan altyazılara sonradan bakmak için; en yenisi en altta. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    val entries = TranslationHistory.entries
    val listState = rememberLazyListState()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    LaunchedEffect(Unit) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Çeviri Geçmişi", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) TextButton(onClick = { TranslationHistory.clear() }) { Text("Temizle") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SectionCardStyle.screenBackground())
            )
        },
        containerColor = SectionCardStyle.screenBackground()
    ) { padding ->
        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Henüz çeviri yok. Başlat'a bastıktan sonra çevrilen altyazılar burada İngilizceleriyle birlikte listelenir. " +
                        "Geçmiş yalnızca uygulama açıkken tutulur.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(entries, key = { it.id }) { entry ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = SectionCardStyle.cardColor(darkAlpha = 0.45f),
                    border = SectionCardStyle.cardBorder()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            timeFormat.format(Date(entry.time)) + if (entry.area > 1) "  ·  Alan ${entry.area}" else "",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(entry.source, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(entry.translation, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
