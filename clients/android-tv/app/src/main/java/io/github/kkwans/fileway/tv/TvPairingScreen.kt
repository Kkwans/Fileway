package io.github.kkwans.fileway.tv

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import io.github.kkwans.fileway.remote.TvHostPhase
import androidx.tv.material3.Icon
import androidx.compose.ui.res.painterResource
import io.github.kkwans.fileway.design.R as Art
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class TvPairingImage(val bitmap:Bitmap?=null,val error:String?=null)

@Composable fun TvPairingScreen(model:TvModel) {
    val host by model.remote.state.collectAsStateWithLifecycle()
    if(!host.pairingOpen && host.invitation==null)return
    val invitation=host.invitation
    BackHandler { model.remote.dismissPairing() }
    val image by produceState(TvPairingImage(),invitation?.id) {
        value=if(invitation==null)TvPairingImage() else withContext(Dispatchers.Default) {
            runCatching {
                val matrix=QRCodeWriter().encode(invitation.uri(),BarcodeFormat.QR_CODE,600,600,mapOf(EncodeHintType.MARGIN to 3))
                val pixels=IntArray(matrix.width*matrix.height) { i -> if(matrix[i%matrix.width,i/matrix.width])android.graphics.Color.BLACK else android.graphics.Color.WHITE }
                TvPairingImage(Bitmap.createBitmap(pixels,matrix.width,matrix.height,Bitmap.Config.ARGB_8888))
            }.getOrElse { TvPairingImage(error="二维码未能生成，请重试") }
        }
    }
    Dialog(onDismissRequest=model.remote::dismissPairing,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        TvRemoteDialogKeys(model.remote::dismissPairing)
        val close=remember { FocusRequester() }
        LaunchedEffect(Unit) { withFrameNanos {};close.requestFocus() }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),contentAlignment=Alignment.Center) {
            Row(Modifier.padding(32.dp).widthIn(max=800.dp),horizontalArrangement=Arrangement.spacedBy(28.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(264.dp).background(if(image.bitmap!=null)Color.White else MaterialTheme.colorScheme.surface,TvShape),contentAlignment=Alignment.Center) {
                    val bitmap=image.bitmap
                    if(bitmap!=null)Image(bitmap.asImageBitmap(),"手机配对二维码",Modifier.fillMaxSize())
                    else if(host.pairingBusy)Text("正在准备连接…",style=MaterialTheme.typography.bodyLarge)
                    else Icon(painterResource(Art.drawable.fileway_ic_phone),null,Modifier.size(56.dp),tint=LocalTvSurfaces.current.secondaryText)
                }
                Column(Modifier.widthIn(max=360.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text("连接手机",style=MaterialTheme.typography.titleLarge)
                    Text("在手机栖卷中打开「连接 TV」，扫描二维码。",style=MaterialTheme.typography.bodyLarge)
                    host.device?.name?.takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodyMedium,color=LocalTvSurfaces.current.secondaryText) }
                    (host.pairingError?:image.error?:host.error?.takeIf { host.phase==TvHostPhase.FAILED })?.let { Text(it,style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.error) }
                    if(host.invitation!=null && host.pairingError==null)host.error?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=LocalTvSurfaces.current.secondaryText) }
                    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        TvButton(if(host.pairingBusy)"正在准备…" else if(invitation!=null)"刷新二维码" else "重试",model.remote::requestPairing,enabled=!host.pairingBusy)
                        TvButton("关闭",model.remote::dismissPairing,Modifier.focusRequester(close))
                    }
                }
            }
        }
    }
}
