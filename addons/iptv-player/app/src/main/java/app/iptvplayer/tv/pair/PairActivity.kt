package app.iptvplayer.tv.pair

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import app.iptvplayer.tv.R
import app.iptvplayer.tv.ui.GlowBackground
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * "Add from phone": shows a QR code, the address and a code. A phone or computer on the same network
 * opens the page the TV serves, sends a playlist, and this screen closes with it saved and opened.
 * The page closes with the screen, and on its own after 15 minutes.
 */
class PairActivity : Activity() {
    private var server: PairServer? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair)
        findViewById<View>(R.id.root).background = GlowBackground()
        findViewById<View>(R.id.btn_close).setOnClickListener { finish() }
    }

    override fun onStart() {
        super.onStart()
        val status = findViewById<TextView>(R.id.status)
        val ip = PairServer.lanAddress()
        val s = PairServer(this) { p ->
            status.text = getString(R.string.pair_added, p.name)
            setResult(RESULT_OK)
            handler.postDelayed({ finish() }, 1800)
        }
        if (ip == null || !s.start()) {
            status.setText(R.string.pair_no_network)
            findViewById<View>(R.id.details).visibility = View.INVISIBLE
            return
        }
        server = s
        val base = "http://$ip:${s.port}"
        findViewById<TextView>(R.id.address).text = base.removePrefix("http://")
        findViewById<TextView>(R.id.code).text = s.pin.chunked(3).joinToString(" ")
        findViewById<ImageView>(R.id.qr).setImageBitmap(qr("$base/?pin=${s.pin}", 600))
        status.setText(R.string.pair_waiting)
        handler.postDelayed({ finish() }, 15 * 60_000L)
        findViewById<View>(R.id.btn_close).requestFocus()
    }

    override fun onStop() {
        server?.stop()
        server = null
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private fun qr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
    }
}
