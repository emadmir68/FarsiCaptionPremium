package com.farsicaption.premium

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HomeScreen(
    modelReady: Boolean, engineLabel: String, engineDownload: String,
    onPickVideo: () -> Unit, canResume: Boolean, onResume: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Ink).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal=22.dp, vertical=20.dp),
        verticalArrangement=Arrangement.spacedBy(22.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
            Surface(color=Accent, shape=RoundedCornerShape(14.dp)) {
                Text("فا", color=Ink, fontSize=24.sp, fontWeight=FontWeight.Black, modifier=Modifier.padding(horizontal=13.dp, vertical=5.dp))
            }
            Column(Modifier.padding(start=12.dp)) {
                Text("فارسی کپشن", color=TextPrimary, fontSize=16.sp, fontWeight=FontWeight.Bold)
                Text("استودیوی زیرنویس", color=TextMuted, fontSize=11.sp)
            }
            Spacer(Modifier.weight(1f))
            StudioPill("آنلاین + آفلاین", Mint)
        }
        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("صدایت را\nدیدنی کن.", color=TextPrimary, fontSize=38.sp, lineHeight=49.sp, fontWeight=FontWeight.Black)
            Text("از یک ویدیو، تا زیرنویس به سبک خودت.", color=TextMuted, fontSize=14.sp)
        }
        Card(onClick=onPickVideo, shape=RoundedCornerShape(28.dp), colors=CardDefaults.cardColors(containerColor=Panel), border=BorderStroke(1.dp,Stroke)) {
            Box(Modifier.fillMaxWidth().height(175.dp).background(Brush.linearGradient(listOf(Color(0xFF29244F), Color(0xFF173B43), Panel)))) {
                WaveArtwork(Modifier.fillMaxSize().padding(horizontal=26.dp,vertical=34.dp))
                Surface(color=Ink.copy(alpha=0.90f), shape=RoundedCornerShape(14.dp), modifier=Modifier.align(Alignment.Center).padding(horizontal=18.dp)) {
                    Text("هر صدا، یک داستان", color=Color.White, fontWeight=FontWeight.Bold, fontSize=22.sp, modifier=Modifier.padding(horizontal=18.dp,vertical=9.dp))
                }
                Text("۹:۱۶  •  ۱۶:۹  •  ۱:۱", color=Mint, fontSize=11.sp, modifier=Modifier.align(Alignment.BottomCenter).padding(bottom=12.dp))
            }
            Column(Modifier.padding(18.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Button(onClick=onPickVideo, modifier=Modifier.fillMaxWidth().heightIn(min=52.dp), shape=RoundedCornerShape(16.dp), colors=ButtonDefaults.buttonColors(containerColor=Mint,contentColor=Ink)) {
                    Text("＋  شروع با یک ویدیو", fontSize=16.sp, fontWeight=FontWeight.Bold)
                }
                Text("انتخاب از گالری  ·  آنلاین یا روی گوشی", color=TextMuted, fontSize=12.sp, modifier=Modifier.align(Alignment.CenterHorizontally))
            }
        }
        if(canResume) OutlinedButton(onClick=onResume, modifier=Modifier.fillMaxWidth().heightIn(min=50.dp), shape=RoundedCornerShape(16.dp), border=BorderStroke(1.dp,Stroke)) {
            Text("ادامهٔ پروژهٔ قبلی", color=AccentSoft, fontWeight=FontWeight.SemiBold)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
            WorkflowStep("۱", "انتخاب ویدیو")
            WorkflowStep("۲", "ساخت و ویرایش")
            WorkflowStep("۳", "ذخیره در گالری")
        }
        Surface(color=Panel, shape=RoundedCornerShape(20.dp), border=BorderStroke(1.dp,Stroke)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement=Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                    Text(if(modelReady) "موتور آمادهٔ کار است" else "آماده‌سازی در اولین استفاده", color=TextPrimary, fontWeight=FontWeight.SemiBold, fontSize=13.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if(modelReady) "●" else "↓", color=if(modelReady) Mint else Accent, fontSize=16.sp)
                }
                Text(if(modelReady) "$engineLabel · پردازش آفلاین" else "$engineLabel · دانلود اولیه $engineDownload", color=TextMuted, fontSize=11.sp)
            }
        }
        Text("فارسی در اولویت، خلاقیت در دست تو.", color=TextMuted, fontSize=11.sp, modifier=Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
internal fun StudioPill(text: String, color: Color = Accent) {
    Surface(color=color.copy(alpha=0.12f), shape=RoundedCornerShape(50)) {
        Text(text, color=color, fontSize=11.sp, fontWeight=FontWeight.SemiBold, modifier=Modifier.padding(horizontal=11.dp,vertical=6.dp))
    }
}

@Composable
private fun WorkflowStep(number: String, label: String) {
    Column(horizontalAlignment=Alignment.CenterHorizontally, verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(number, color=Accent, fontSize=16.sp, fontWeight=FontWeight.Bold)
        Text(label, color=TextMuted, fontSize=11.sp)
    }
}

@Composable
private fun WaveArtwork(modifier: Modifier) {
    val levels = listOf(.12f,.25f,.42f,.26f,.6f,.8f,.4f,.65f,.98f,.6f,.4f,.75f,.55f,.3f,.65f,.92f,.5f,.3f,.72f,.4f,.22f,.47f,.68f,.33f,.2f,.4f,.25f,.12f)
    Canvas(modifier) {
        val step=size.width/levels.size
        levels.forEachIndexed { i, level ->
            val h=size.height*level
            drawRoundRect(if(i<levels.size/2) Accent.copy(alpha=.55f) else Mint.copy(alpha=.5f),
                topLeft=Offset(i*step,(size.height-h)/2), size=Size(step*.40f,h), cornerRadius=CornerRadius(step,step))
        }
    }
}
