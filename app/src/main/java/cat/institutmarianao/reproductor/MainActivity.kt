package cat.institutmarianao.reproductor

import android.Manifest
import android.content.ContentUris
import android.media.MediaPlayer
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var songsListView: ListView
    private lateinit var playPauseButton: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var songTitle: TextView
    private lateinit var songArtist: TextView

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var updateSeekbar: Runnable? = null
    private var currentTrackIndex = 0
    private var isPlaying = false
    private var shuffleEnabled = false
    private var repeatEnabled = false

    private val tracks = mutableListOf<Track>()

    data class Track(
        val uri: Uri,
        val title: String,
        val artist: String,
        val duration: Int
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestStoragePermissions()
        initializeComponents()
        setupEventListeners()
        loadAudioTracks()
    }

    private fun requestStoragePermissions() {
        val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        ActivityCompat.requestPermissions(this, arrayOf(requiredPermission), 0)
    }

    private fun initializeComponents() {
        songsListView = findViewById(R.id.songListView)
        playPauseButton = findViewById(R.id.play_stop)
        seekBar = findViewById(R.id.seekBar)
        currentTimeText = findViewById(R.id.txtCurrentTime)
        totalTimeText = findViewById(R.id.txtTotalTime)
        songTitle = findViewById(R.id.songTitle)
        songArtist = findViewById(R.id.songArtist)
    }

    private fun setupEventListeners() {
        songsListView.setOnItemClickListener { _, _, position, _ ->
            playSelectedTrack(position)
        }

        playPauseButton.setOnClickListener {
            togglePlaybackState()
        }

        findViewById<ImageButton>(R.id.next).setOnClickListener {
            playNextTrack()
        }

        findViewById<ImageButton>(R.id.previous).setOnClickListener {
            playPreviousTrack()
        }

        findViewById<ImageButton>(R.id.random).setOnClickListener {
            toggleShuffleMode()
        }

        findViewById<ImageButton>(R.id.bucle).setOnClickListener {
            toggleRepeatMode()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                    currentTimeText.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun loadAudioTracks() {
        val audioCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION
        )

        contentResolver.query(audioCollection, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn)
                val artist = cursor.getString(artistColumn) ?: "Artista desconocido"
                val duration = cursor.getInt(durationColumn)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                tracks.add(Track(contentUri, title, artist, duration))
            }

            populateSongList()
        }
    }

    private fun populateSongList() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            tracks.map { it.title }
        )
        songsListView.adapter = adapter
    }

    private fun playSelectedTrack(position: Int) {
        currentTrackIndex = position
        prepareAndStartPlayback(tracks[position])
    }

    private fun prepareAndStartPlayback(track: Track) {
        mediaPlayer?.release()

        mediaPlayer = MediaPlayer().apply {
            setDataSource(applicationContext, track.uri)
            prepareAsync()

            setOnPreparedListener {
                startPlayback(track)
            }

            setOnCompletionListener {
                handleTrackCompletion()
            }
        }
    }

    private fun startPlayback(track: Track) {
        mediaPlayer?.start()
        isPlaying = true
        updatePlaybackUI(track)
        initializeSeekbarUpdater(track.duration)
    }

    private fun updatePlaybackUI(track: Track) {
        songTitle.text = track.title
        songArtist.text = track.artist
        playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
        totalTimeText.text = formatTime(track.duration)
        seekBar.max = track.duration
    }

    private fun initializeSeekbarUpdater(duration: Int) {
        updateSeekbar = object : Runnable {
            override fun run() {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        val currentPosition = it.currentPosition
                        seekBar.progress = currentPosition
                        currentTimeText.text = formatTime(currentPosition)
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateSeekbar!!)
    }

    private fun formatTime(millis: Int): String {
        val minutes = (millis / 1000) / 60
        val seconds = (millis / 1000) % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun togglePlaybackState() {
        if (isPlaying) {
            mediaPlayer?.pause()
            playPauseButton.setImageResource(android.R.drawable.ic_media_play)
        } else {
            mediaPlayer?.start()
            playPauseButton.setImageResource(android.R.drawable.ic_media_pause)
        }
        isPlaying = !isPlaying
    }

    private fun playNextTrack() {
        when {
            repeatEnabled -> playSelectedTrack(currentTrackIndex)
            shuffleEnabled -> playRandomTrack()
            else -> {
                currentTrackIndex = if (currentTrackIndex < tracks.lastIndex) currentTrackIndex + 1 else 0
                playSelectedTrack(currentTrackIndex)
            }
        }
    }

    private fun playPreviousTrack() {
        mediaPlayer?.let {
            if (it.currentPosition > 5000) {
                it.seekTo(0)
            } else {
                currentTrackIndex = if (currentTrackIndex > 0) currentTrackIndex - 1 else tracks.lastIndex
                playSelectedTrack(currentTrackIndex)
            }
        }
    }

    private fun playRandomTrack() {
        val newIndex = (tracks.indices).random().let {
            if (it == currentTrackIndex) (it + 1) % tracks.size else it
        }
        currentTrackIndex = newIndex
        playSelectedTrack(newIndex)
    }

    private fun toggleShuffleMode() {
        shuffleEnabled = !shuffleEnabled
        findViewById<ImageButton>(R.id.random).setImageResource(
            if (shuffleEnabled) R.drawable.baseline_shuffle_24 else R.drawable.baseline_shuffle_24
        )
    }

    private fun toggleRepeatMode() {
        repeatEnabled = !repeatEnabled
        findViewById<ImageButton>(R.id.bucle).setImageResource(
            if (repeatEnabled) R.drawable.baseline_repeat_24 else R.drawable.baseline_repeat_24
        )
    }

    private fun handleTrackCompletion() {
        when {
            repeatEnabled -> playSelectedTrack(currentTrackIndex)
            shuffleEnabled -> playRandomTrack()
            else -> playNextTrack()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        handler.removeCallbacksAndMessages(null)
    }
}