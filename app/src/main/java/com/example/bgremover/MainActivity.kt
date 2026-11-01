package com.example.bgremover

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var editor: EditorView

    private val picker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { load(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        editor = findViewById(R.id.editor)
        val density = resources.displayMetrics.density

        findViewById<Button>(R.id.btnOpen).setOnClickListener { picker.launch("image/*") }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { editor.undo() }
        findViewById<Button>(R.id.btnReset).setOnClickListener { editor.resetImage() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }

        findViewById<RadioGroup>(R.id.modeGroup).setOnCheckedChangeListener { _, id ->
            editor.mode = when (id) {
                R.id.rbRestore -> EditorView.Mode.RESTORE
                R.id.rbLasso -> EditorView.Mode.LASSO
                R.id.rbWand -> EditorView.Mode.WAND
                R.id.rbPan -> EditorView.Mode.PAN
                else -> EditorView.Mode.ERASE
            }
        }

        val seekSize = findViewById<SeekBar>(R.id.seekSize)
        editor.brushSize = maxOf(4, seekSize.progress) * density
        seekSize.setOnSeekBarChangeListener(simpleSeek { editor.brushSize = maxOf(4, it) * density })

        val seekTol = findViewById<SeekBar>(R.id.seekTol)
        editor.tolerance = seekTol.progress
        seekTol.setOnSeekBarChangeListener(simpleSeek { editor.tolerance = it })

        findViewById<CheckBox>(R.id.chkKeep).setOnCheckedChangeListener { _, checked ->
            editor.lassoKeepInside = checked
        }
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = onChange(p)
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun load(uri: Uri) {
        try {
            val src = ImageDecoder.createSource(contentResolver, uri)
            val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val m = maxOf(w, h)
                if (m > MAX_SIZE) {
                    val s = MAX_SIZE.toFloat() / m
                    decoder.setTargetSize(
                        (w * s).toInt().coerceAtLeast(1),
                        (h * s).toInt().coerceAtLeast(1)
                    )
                }
            }
            editor.setImage(bmp)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka gambar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun save() {
        val bmp = editor.result()
        if (bmp == null) {
            Toast.makeText(this, "Buka gambar dulu", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "bg_removed_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BgRemover")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("insert gagal")
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Toast.makeText(this, "Tersimpan di Pictures/BgRemover", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menyimpan", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val MAX_SIZE = 2000
    }
}
