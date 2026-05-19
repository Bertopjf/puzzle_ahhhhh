// MainActivity.kt - Puzzle Detection avec Auto-Detection
package com.puzzledetection.phase1

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.ArrayDeque
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.plant(Timber.DebugTree())
        
        setContent {
            PuzzlePhase1App()
        }
    }
}

data class PhotoMetadata(
    val puzzleId: String,
    val pieceCount: Int,
    val operator: String,
    val timestamp: String,
    val filePath: String,
    val fileSize: Long
)

data class PieceDetectionResult(
    val count: Int,
    val confidence: Float
)

class PuzzleViewModel : ViewModel() {
    val metadata = mutableStateOf<PhotoMetadata?>(null)
    val photoUri = mutableStateOf<Uri?>(null)
    val photoBitmap = mutableStateOf<Bitmap?>(null)
    val isPhotoTaken = mutableStateOf(false)
    val detectedPieceCount = mutableStateOf<Int?>(null)
    val detectionConfidence = mutableStateOf(0f)
    val isDetecting = mutableStateOf(false)
    
    fun savePhoto(context: Context, uri: Uri, puzzleId: String, pieceCount: Int, operator: String) {
        try {
            // Créer dossier session
            val sessionDir = File(
                context.getExternalFilesDir(null),
                "puzzle_${System.currentTimeMillis()}"
            )
            if (!sessionDir.exists()) sessionDir.mkdirs()
            
            // Copier photo
            val photoFile = File(sessionDir, "puzzle.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                photoFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            // Charger bitmap pour preview et détection
            val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
            photoBitmap.value = bitmap
            
            // Sauvegarder métadonnées (sans piece count pour l'instant)
            photoUri.value = uri
            isPhotoTaken.value = true
            
            // Lancer détection de pièces en background
            detectPieceCount(context, photoFile, puzzleId, pieceCount, operator)
            
            Timber.d("Photo saved: ${photoFile.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save photo")
        }
    }
    
    private fun detectPieceCount(context: Context, photoFile: File, puzzleId: String, manualCount: Int, operator: String) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                isDetecting.value = true
                Timber.d("Starting piece detection...")
                
                val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                if (bitmap != null) {
                    val result = countPieces(bitmap)
                    detectedPieceCount.value = result.count
                    detectionConfidence.value = result.confidence
                    
                    Timber.d("Detection complete: ${result.count} pieces, confidence ${result.confidence}")
                    
                    // Sauvegarder métadonnées avec détection
                    val meta = PhotoMetadata(
                        puzzleId = puzzleId,
                        pieceCount = result.count,  // Utiliser le nombre détecté
                        operator = operator,
                        timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()),
                        filePath = photoFile.absolutePath,
                        fileSize = photoFile.length()
                    )
                    metadata.value = meta
                    
                    // Sauvegarder aussi les infos de détection
                    val detectionFile = File(photoFile.parentFile, "detection.json")
                    detectionFile.writeText("""
                        {
                          "piece_count_detected": ${result.count},
                          "piece_count_manual": $manualCount,
                          "confidence": ${result.confidence},
                          "confidence_percent": "${(result.confidence * 100).toInt()}%"
                        }
                    """.trimIndent())
                    
                    bitmap.recycle()
                }
                
                isDetecting.value = false
            } catch (e: Exception) {
                Timber.e(e, "Piece detection failed: ${e.message}")
                isDetecting.value = false
            }
        }
    }
    
    private fun countPieces(bitmap: Bitmap): PieceDetectionResult {
        val width = bitmap.width
        val height = bitmap.height
        
        // Convert to grayscale
        val gray = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                gray[y * width + x] = (0.3f * r + 0.59f * g + 0.11f * b).toInt()
            }
        }
        
        // Otsu threshold
        val threshold = otsuThreshold(gray)
        val binary = BooleanArray(width * height) { gray[it] > threshold }
        
        // Morphological operations
        morphOpen(binary, width, height, 5)
        morphClose(binary, width, height, 5)
        
        // Label connected components
        val labels = IntArray(width * height) { -1 }
        var componentCount = 0
        
        for (i in 0 until width * height) {
            if (binary[i] && labels[i] == -1) {
                floodFill(i, width, height, binary, labels, componentCount)
                componentCount++
            }
        }
        
        // Filter by size
        val areas = IntArray(componentCount)
        for (i in 0 until width * height) {
            if (labels[i] >= 0) {
                areas[labels[i]]++
            }
        }
        
        val meanArea = areas.average()
        val stdArea = sqrt(areas.map { (it - meanArea) * (it - meanArea) }.average())
        
        val minArea = meanArea * 0.25
        val maxArea = meanArea * 4.0
        
        val validPieces = areas.count { it in minArea..maxArea }
        
        // Confidence based on uniformity
        val cv = if (meanArea > 0) stdArea / meanArea else 1.0
        val confidence = maxOf(0f, minOf(1f, (1f - cv / 2f).toFloat()))
        
        Timber.d("Detection: $validPieces pieces, mean area: $meanArea, CV: $cv, confidence: $confidence")
        
        return PieceDetectionResult(validPieces, confidence)
    }
    
    private fun otsuThreshold(gray: IntArray): Int {
        val histogram = IntArray(256)
        for (g in gray) {
            histogram[g.coerceIn(0, 255)]++
        }
        
        val total = gray.size
        var sumB = 0
        var countB = 0
        var sumT = 0
        
        for (i in 0..255) {
            sumT += i * histogram[i]
        }
        
        var maxVar = 0.0
        var threshold = 0
        
        for (t in 0..255) {
            countB += histogram[t]
            if (countB == 0) continue
            
            val countF = total - countB
            if (countF == 0) break
            
            sumB += t * histogram[t]
            val meanB = sumB.toDouble() / countB
            val meanF = (sumT - sumB).toDouble() / countF
            
            val varBetween = countB.toDouble() * countF * (meanB - meanF) * (meanB - meanF)
            
            if (varBetween > maxVar) {
                maxVar = varBetween
                threshold = t
            }
        }
        
        return threshold
    }
    
    private fun floodFill(start: Int, width: Int, height: Int, binary: BooleanArray, labels: IntArray, label: Int) {
        val queue = ArrayDeque<Int>()
        queue.add(start)
        labels[start] = label
        
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val x = idx % width
            val y = idx / width
            
            val neighbors = listOf(
                if (y > 0) (y - 1) * width + x else -1,
                if (y < height - 1) (y + 1) * width + x else -1,
                if (x > 0) y * width + (x - 1) else -1,
                if (x < width - 1) y * width + (x + 1) else -1
            )
            
            for (nIdx in neighbors) {
                if (nIdx >= 0 && nIdx < width * height && binary[nIdx] && labels[nIdx] == -1) {
                    labels[nIdx] = label
                    queue.add(nIdx)
                }
            }
        }
    }
    
    private fun morphOpen(binary: BooleanArray, width: Int, height: Int, kernelSize: Int) {
        val temp = binary.copyOf()
        morphErode(binary, temp, width, height, kernelSize)
        morphDilate(temp, binary, width, height, kernelSize)
    }
    
    private fun morphClose(binary: BooleanArray, width: Int, height: Int, kernelSize: Int) {
        val temp = binary.copyOf()
        morphDilate(binary, temp, width, height, kernelSize)
        morphErode(temp, binary, width, height, kernelSize)
    }
    
    private fun morphErode(src: BooleanArray, dst: BooleanArray, width: Int, height: Int, kernelSize: Int) {
        val radius = kernelSize / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                var allTrue = true
                for (ky in -radius..radius) {
                    for (kx in -radius..radius) {
                        val nx = x + kx
                        val ny = y + ky
                        if (nx in 0 until width && ny in 0 until height) {
                            if (!src[ny * width + nx]) allTrue = false
                        }
                    }
                }
                dst[y * width + x] = allTrue
            }
        }
    }
    
    private fun morphDilate(src: BooleanArray, dst: BooleanArray, width: Int, height: Int, kernelSize: Int) {
        val radius = kernelSize / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                var anyTrue = false
                for (ky in -radius..radius) {
                    for (kx in -radius..radius) {
                        val nx = x + kx
                        val ny = y + ky
                        if (nx in 0 until width && ny in 0 until height) {
                            if (src[ny * width + nx]) anyTrue = true
                        }
                    }
                }
                dst[y * width + x] = anyTrue
            }
        }
    }
    
    fun reset() {
        metadata.value = null
        photoUri.value = null
        photoBitmap.value = null
        isPhotoTaken.value = false
        detectedPieceCount.value = null
        detectionConfidence.value = 0f
        isDetecting.value = false
    }
}

@Composable
fun PuzzlePhase1App() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00D4FF),
            secondary = Color(0xFF7C3AED),
            tertiary = Color(0xFF06B6D4),
            background = Color(0xFF0F172A),
            surface = Color(0xFF1E293B),
            surfaceVariant = Color(0xFF334155)
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val viewModel: PuzzleViewModel = viewModel()
            
            if (viewModel.isPhotoTaken.value) {
                ResultScreen(viewModel)
            } else {
                AcquisitionScreen(viewModel)
            }
        }
    }
}

@Composable
fun AcquisitionScreen(viewModel: PuzzleViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    var puzzleId by remember { mutableStateOf("") }
    var pieceCount by remember { mutableStateOf("") }
    var operator by remember { mutableStateOf("") }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && currentPhotoUri != null) {
            viewModel.savePhoto(context, currentPhotoUri!!, puzzleId, pieceCount.toIntOrNull() ?: 0, operator)
        }
    }
    
    var currentPhotoUri: Uri? = remember { null }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "PUZZLE DETECTION",
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary
        )
        
        Text(
            text = "Phase 1 • Auto-Detection",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.surfaceVariant
        )
        
        Divider(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
            thickness = 1.dp,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        
        OutlinedTextField(
            value = puzzleId,
            onValueChange = { puzzleId = it },
            label = { Text("Puzzle ID") },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )
        
        OutlinedTextField(
            value = pieceCount,
            onValueChange = { pieceCount = it },
            label = { Text("Piece Count (optional)") },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp),
            singleLine = true,
            supportingText = { Text("Leave blank — we'll detect it automatically") }
        )
        
        OutlinedTextField(
            value = operator,
            onValueChange = { operator = it },
            label = { Text("Operator Name") },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .size(16.dp)
                            .padding(end = 8.dp)
                    )
                    Text(
                        text = "Auto-Detection",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                
                Text(
                    text = "Place puzzle pieces separated on a uniform background. The app will automatically detect the number of pieces.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = {
                if (puzzleId.isNotBlank()) {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.CAMERA
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                    
                    val photoFile = File(
                        context.cacheDir,
                        "temp_puzzle_${System.currentTimeMillis()}.jpg"
                    )
                    currentPhotoUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        photoFile
                    )
                    cameraLauncher.launch(currentPhotoUri)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Filled.Camera,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .padding(end = 8.dp)
            )
            Text("TAKE PHOTO", fontWeight = FontWeight.Bold)
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun ResultScreen(viewModel: PuzzleViewModel) {
    val metadata = viewModel.metadata.value ?: return
    val bitmap = viewModel.photoBitmap.value
    val isDetecting = viewModel.isDetecting.value
    val detectedCount = viewModel.detectedPieceCount.value
    val confidence = viewModel.detectionConfidence.value
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "CAPTURED",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        
        if (bitmap != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Puzzle photo",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    contentScale = ContentScale.Crop
                )
            }
        }
        
        if (isDetecting) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        "Detecting pieces...",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else if (detectedCount != null && detectedCount > 0) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(16.dp)
                                .padding(end = 8.dp)
                        )
                        Text(
                            "Auto-Detection",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$detectedCount pieces",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        Text(
                            text = "${(confidence * 100).toInt()}%",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetadataRow("Puzzle ID", metadata.puzzleId)
                MetadataRow("Pieces (detected)", metadata.pieceCount.toString())
                MetadataRow("Operator", metadata.operator)
                MetadataRow("Timestamp", metadata.timestamp)
                MetadataRow("File Size", "${metadata.fileSize / 1024} KB")
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = { viewModel.reset() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Filled.CameraAlt,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .padding(end = 8.dp)
            )
            Text("TAKE ANOTHER", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MetadataRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.surfaceVariant,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            fontSize = 13.sp,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}
