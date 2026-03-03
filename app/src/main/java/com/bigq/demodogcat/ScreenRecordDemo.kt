package com.bigq.demodogcat

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bigq.demodogcat.saytheword.BoxWordAnimation

@Composable
fun ScreenRecordDemo(
    onStartRecord: () -> Unit,
) {
    var recording by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(bottom = 30.dp)
    ) {
        if (recording) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                BoxWordAnimation(
                    isRecording = recording
                )
            }
        }
        // Nội dung chính
        Box(
            modifier = Modifier
                .fillMaxSize()
        ){
            CameraPreview()
        }

        // Nút record
        Button(
            modifier = Modifier
                .size(80.dp)
                .align(Alignment.BottomCenter),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (recording) Color.Red else Color.White
            ),
            onClick = {
                if (recording) {
                    val intent = Intent(context, ScreenRecordService::class.java)
                    intent.action = ScreenRecordService.ACTION_STOP
                    context.startService(intent)
                } else {
                    onStartRecord()
                }
            }
        ) {
            if (recording) {
                // Stop icon (square)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color.White, RoundedCornerShape(4.dp))
                )
            } else {
                // Record icon (red circle)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color.Red, CircleShape)
                )
            }
        }
    }
}