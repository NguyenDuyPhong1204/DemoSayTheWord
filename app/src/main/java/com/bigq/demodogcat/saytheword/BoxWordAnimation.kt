package com.bigq.demodogcat.saytheword

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
@Composable
fun BoxWordAnimation(
    isRecording: Boolean
) {
    // Chỉ chạy lần đầu
    var isFirstLaunch by rememberSaveable { mutableStateOf(true) }

    val visibleStates = remember {
        List(wordList.size) { Animatable(0f) }
    }

    var activeBorderIndex by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        if(isRecording){
            if (isFirstLaunch) {

                // 1️⃣ Hiện từng item từ trái sang phải
                visibleStates.forEach { anim ->
                    anim.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(300)
                    )
                }

                // 2️⃣ Delay 0.5s
                delay(500)

                // 3️⃣ Chạy border xanh lần lượt
                for (i in wordList.indices) {
                    activeBorderIndex = i
                    delay(500)
                }

                isFirstLaunch = false
            }
        }
    }


    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .background(Color.White)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                        alpha = visibleStates[index].value,
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
    alpha: Float,
    isActive: Boolean,
) {
    val borderColor by animateColorAsState(
        targetValue = if (isActive) Color(0xFF00C853) else Color.Black,
        animationSpec = tween(300),
        label = ""
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                translationY = (1f - alpha) * 40f
            }
            .border(width = 1.5.dp, color = borderColor)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(item.image),
            contentDescription = null,
            modifier = Modifier.size(70.dp),
            contentScale = ContentScale.Crop
        )

        Spacer(Modifier.height(10.dp))

        Text(
            text = item.label,
        )
    }
}