package io.github.kkwans.fileway.tv

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun TvPairingScreen(model:TvModel){
    val host by model.remote.state.collectAsStateWithLifecycle()
    val invitation=host.invitation?:return
    BackHandler { model.remote.dismissPairing() }
    val bitmap by produceState<Bitmap?>(null,invitation.id){
        value=withContext(Dispatchers.Default){
            val matrix=QRCodeWriter().encode(invitation.uri(),BarcodeFormat.QR_CODE,600,600,mapOf(EncodeHintType.MARGIN to 3))
            val pixels=IntArray(matrix.width*matrix.height){i->if(matrix[i%matrix.width,i/matrix.width])android.graphics.Color.BLACK else android.graphics.Color.WHITE}
            Bitmap.createBitmap(pixels,matrix.width,matrix.height,Bitmap.Config.ARGB_8888)
        }
    }
    Dialog(onDismissRequest=model.remote::dismissPairing,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
    TvRemoteDialogKeys(model.remote::dismissPairing)
    val close=remember{FocusRequester()}
    LaunchedEffect(invitation.id){withFrameNanos{};close.requestFocus()}
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),contentAlignment=Alignment.Center){
        Row(Modifier.padding(32.dp).widthIn(max=800.dp),horizontalArrangement=Arrangement.spacedBy(32.dp),verticalAlignment=Alignment.CenterVertically){
            bitmap?.let{Image(it.asImageBitmap(),"手机配对二维码",Modifier.size(264.dp))}
            Column(Modifier.widthIn(max=360.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
                Text("用手机连接栖卷 TV",style=MaterialTheme.typography.titleLarge)
                Text("手机栖卷 · 连接 TV · 扫码配对",style=MaterialTheme.typography.bodyLarge)
                Text(host.device?.name.orEmpty(),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurface.copy(alpha=.7f))
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    TvButton("刷新二维码",model.remote::requestPairing)
                    TvButton("关闭",model.remote::dismissPairing,Modifier.focusRequester(close))
                }
            }
        }
    }
    }
}
