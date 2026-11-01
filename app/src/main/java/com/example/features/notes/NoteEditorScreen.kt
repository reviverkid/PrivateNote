package com.example.features.notes

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.NoteEntity
import com.example.data.local.NotesDatabase
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    noteId: Long?,
    viewModel: NotesViewModel,
    onBack: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val noteDao = remember { NotesDatabase.getDatabase(context).noteDao() }
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("Notes") }
    var existingNote by remember { mutableStateOf<NoteEntity?>(null) }

    LaunchedEffect(noteId) {
        if (noteId != null && noteId > 0L) {
            val note = noteDao.getNoteById(noteId)
            if (note != null) {
                existingNote = note
                title = note.title
                content = note.content
                folder = note.folder
            }
        }
    }

    fun saveNote() {
        if (title.isBlank() && content.isBlank()) {
            onBack()
            return
        }
        scope.launch {
            if (existingNote != null) {
                noteDao.updateNote(
                    existingNote!!.copy(
                        title = title,
                        content = content,
                        folder = folder,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else {
                noteDao.insertNote(
                    NoteEntity(
                        title = title,
                        content = content,
                        folder = folder
                    )
                )
            }
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (noteId == null) "New Note" else "Edit Note") },
                navigationIcon = {
                    IconButton(onClick = {
                        saveNote()
                    }, modifier = Modifier.testTag("back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (existingNote != null) {
                        IconButton(onClick = {
                            scope.launch {
                                noteDao.deleteNote(existingNote!!)
                                onBack()
                            }
                        }, modifier = Modifier.testTag("delete_note_button")) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Note", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { saveNote() }, modifier = Modifier.testTag("save_note_button")) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Title") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("note_title_input"),
                textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = { Text("Start typing your note...") },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("note_content_input"),
                textStyle = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
