package com.rise.blescanner.adapters

import android.content.Intent
import android.os.Environment
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import com.rise.blescanner.JsonViewActivity
import com.rise.blescanner.databinding.ItemRecordingBinding
import com.rise.blescanner.models.RecordingInfo
import java.io.File

class RecordAdapter(
    private val recordingList: List<RecordingInfo>
) : RecyclerView.Adapter<RecordAdapter.RecordViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
        val binding = ItemRecordingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RecordViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
        holder.bind(recordingList[position])
    }

    override fun getItemCount(): Int = recordingList.size

    class RecordViewHolder(
        private val binding: ItemRecordingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(recording: RecordingInfo) {
            val file = File(recording.csvLocation)
            binding.txtFileName.text = file.name
            binding.txtDate.text = recording.date
            binding.txtDuration.text = recording.duration
            binding.txtDataPoints.text = recording.entryCount.toString()

            val context = binding.root.context

            binding.btnView.setOnClickListener {
                if (!file.exists()) {
                    Toast.makeText(context, "Recording file does not exist", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val intent = Intent(context, JsonViewActivity::class.java).apply {
                    putExtra("json_path", file.absolutePath)
                }
                context.startActivity(intent)
            }

            binding.btnShare.setOnClickListener {
                if (!file.exists()) {
                    Toast.makeText(context, "Recording file does not exist", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                try {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "com.rise.blescanner.fileprovider",
                        file
                    )
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/json"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share Telemetry JSON"))
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to share: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            binding.btnExport.setOnClickListener {
                if (!file.exists()) {
                    Toast.makeText(context, "Recording file does not exist", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                try {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    if (!downloadsDir.exists()) {
                        downloadsDir.mkdirs()
                    }
                    val destFile = File(downloadsDir, file.name)
                    file.copyTo(destFile, overwrite = true)
                    Toast.makeText(context, "Exported to Downloads: ${destFile.name}", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
