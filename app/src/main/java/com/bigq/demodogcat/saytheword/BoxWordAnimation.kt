package com.bigq.demodogcat.saytheword

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import timber.log.Timber

@Composable
fun BoxWordAnimation(
    isRecording: Boolean,
) {
    var activeBorderIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(isRecording) {
        if (!isRecording) {
            activeBorderIndex = -1
            return@LaunchedEffect
        }
        delay(5000)
        for (i in wordList.indices) {
            activeBorderIndex = i

            delay(400)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "1/5",
                fontSize = 16.sp,
                color = Color.Black,
                fontWeight = FontWeight.Black
            )

            Spacer(Modifier.height(10.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                itemsIndexed(wordList) { index, item ->

                    ItemWord(
                        item = item,
                        isActive = index == activeBorderIndex
                    )
                }
            }
        }
    }
}

@Composable
fun ItemWord(
    item: WordItem,
    isActive: Boolean,
) {
    val borderColor =
        if (isActive) Color(0xFF00C853)
        else Color.Black

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 3.dp, color = borderColor)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(item.image),
            contentDescription = null,
            modifier = Modifier.size(50.dp),
            contentScale = ContentScale.Crop
        )

        Spacer(Modifier.height(10.dp))

        Text(text = item.label)
    }
}