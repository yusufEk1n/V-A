package com.example.virtualassistant.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.aallam.openai.api.BetaOpenAI
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.example.virtualassistant.R
import com.example.virtualassistant.assistant.adapters.ChatAdapter
import com.example.virtualassistant.assistant.onboarding.WelcomeActivity
import com.example.virtualassistant.assistant.settings.SettingsActivity
import com.example.virtualassistant.assistant.ui.MicrophonePermissionScreen
import com.example.virtualassistant.assistant.util.TextToSpeechManager
import com.google.cloud.texttospeech.v1.TextToSpeechClient
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.cdimascio.dotenv.Dotenv
import io.ktor.util.reflect.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale


class MainActivity : FragmentActivity() {

    // Init UI
    private var messageInput: EditText? = null
    private var btnSend: ImageButton? = null
    private var btnMicro: ImageButton? = null
    private var btnSettings: ImageButton? = null
    private var btnKeyboard: ImageButton? = null
    private var keyboardInput: LinearLayout? = null
    private var progress: ProgressBar? = null
    private var chat: ListView? = null
    private var activityTitle: TextView? = null
    private var btnDebugTTS: Button? = null

    // Init chat
    private var messages: ArrayList<Map<String, Any>> = ArrayList()
    private var adapter: ChatAdapter? = null

    // Init states
    private var isRecording = false
    private var keyboardMode = false
    private var doesTTSSpeaking = false

    // init AI
    private var ai: OpenAI? = null
    private var key: String? = null

    private var mediaPlayer: MediaPlayer? = null
    private var audioData: ByteArray? = null
    private var textToSpeechManager: TextToSpeechManager? = null

    // Init audio
    private var recognizer: SpeechRecognizer? = null
    private val speechListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { /* unused */
        }

        override fun onBeginningOfSpeech() { /* unused */
        }

        override fun onRmsChanged(rmsdB: Float) { /* unused */
        }

        override fun onBufferReceived(buffer: ByteArray?) { /* unused */
        }

        override fun onPartialResults(partialResults: Bundle?) { /* unused */
        }

        override fun onEvent(eventType: Int, params: Bundle?) { /* unused */
        }

        override fun onEndOfSpeech() {
            btnMicro?.setImageResource(R.drawable.ic_microphone)
        }

        override fun onError(error: Int) {
            isRecording = false
            btnMicro?.setImageResource(R.drawable.ic_microphone)
        }

        override fun onResults(results: Bundle?) {
            btnMicro?.setImageResource(R.drawable.ic_stop_recording)
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (matches != null && matches.size > 0) {
                val recognizedText = matches[0]

                putMessage(recognizedText, false)

                btnMicro?.isEnabled = false
                btnSend?.isEnabled = false
                progress?.visibility = View.VISIBLE

                CoroutineScope(Dispatchers.Main).launch {
                    generateResponse(recognizedText, true)
                }
            }
        }
    }

    // Init permissions screen
    private val permissionResultLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            run {
                if (result.resultCode == Activity.RESULT_OK) {
                    startRecognition()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        initSettings()
    }

    public override fun onDestroy() {
        if (textToSpeechManager != null) {
            textToSpeechManager!!.close()
        }

        super.onDestroy()
    }

    /** SYSTEM INITIALIZATION START **/

    private fun initSettings() {
        val settings: SharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)

        key = settings.getString("api_key", null)

        if (key == null) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
        } else {
            val chat: SharedPreferences = getSharedPreferences("chat", MODE_PRIVATE)

            messages = try {
                val gson = Gson()
                val json = chat.getString("chat", null)
                val type: Type = object : TypeToken<ArrayList<Map<String, Any>?>?>() {}.type

                gson.fromJson<Any>(json, type) as ArrayList<Map<String, Any>>
            } catch (e: Exception) {
                ArrayList()
            }

            adapter = ChatAdapter(messages, this)

            initUI()
            initSpeechListener()
            initTTS()
            initLogic()
            initAI()
        }
    }

    private fun saveSettings() {
        val chat = getSharedPreferences("chat", MODE_PRIVATE)
        val editor = chat.edit()
        val gson = Gson()
        val json: String = gson.toJson(messages)

        editor.putString("chat", json)
        editor.apply()
    }

    @SuppressLint("SetTextI18n")
    private fun initUI() {
        btnMicro = findViewById(R.id.btn_micro)
        btnSettings = findViewById(R.id.btn_settings)
        btnKeyboard = findViewById(R.id.btn_keyboard)
        keyboardInput = findViewById(R.id.keyboard_input)
        chat = findViewById(R.id.messages)
        messageInput = findViewById(R.id.message_input)
        btnSend = findViewById(R.id.btn_send)
        progress = findViewById(R.id.progress)
        activityTitle = findViewById(R.id.activity_title)
        btnDebugTTS = findViewById(R.id.btn_debug_tts)

        try {
            val pInfo: PackageInfo = this.packageManager.getPackageInfo(this.packageName, 0)
            val version = pInfo.versionName
            activityTitle?.text = "${resources.getString(R.string.app_name)} $version"
        } catch (e: PackageManager.NameNotFoundException) {
            activityTitle?.text = resources.getString(R.string.app_name)
        }

        progress?.visibility = View.GONE

        btnMicro?.setImageResource(R.drawable.ic_microphone)
        btnSettings?.setImageResource(R.drawable.ic_settings)
        btnKeyboard?.setImageResource(R.drawable.ic_keyboard)

        keyboardInput?.visibility = View.GONE

        chat?.adapter = adapter
        chat?.divider = ColorDrawable(0x3D000000)
        chat?.dividerHeight = 1

        adapter?.notifyDataSetChanged()
    }

    private fun initLogic() {
        btnMicro?.setOnClickListener {
            if (isRecording) {

                if (mediaPlayer != null) {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                    mediaPlayer = null
                }

                textToSpeechManager?.close()

                btnMicro?.setImageResource(R.drawable.ic_microphone)
                recognizer?.stopListening()
                isRecording = false
            } else {
                btnMicro?.setImageResource(R.drawable.ic_stop_recording)
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    startRecognition()
                } else {
                    permissionResultLauncher.launch(
                        Intent(
                            this,
                            MicrophonePermissionScreen::class.java
                        )
                    )
                }

                isRecording = true
            }
        }

        btnKeyboard?.setOnClickListener {
            if (keyboardMode) {
                keyboardMode = false
                keyboardInput?.visibility = View.GONE
                btnKeyboard?.setImageResource(R.drawable.ic_keyboard)
            } else {
                keyboardMode = true
                keyboardInput?.visibility = View.VISIBLE
                btnKeyboard?.setImageResource(R.drawable.ic_keyboard_hide)
            }
        }

        btnSend?.setOnClickListener {
            if (messageInput?.text.toString() != "") {
                val message: String = messageInput?.text.toString()

                messageInput?.setText("")

                //hide keyboard
                hideKeyboard(it)

                keyboardMode = false
                keyboardInput?.visibility = View.GONE
                btnKeyboard?.setImageResource(R.drawable.ic_keyboard)

                putMessage(message, false)

                btnMicro?.isEnabled = false
                btnSend?.isEnabled = false
                progress?.visibility = View.VISIBLE

                CoroutineScope(Dispatchers.Main).launch {
                    generateResponse(message, false)
                }
            }
        }

        btnSettings?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnDebugTTS?.setOnClickListener {
            if (!doesTTSSpeaking) {
                doesTTSSpeaking = true
                textToSpeech("Android is a mobile operating system developed by Google, based on the Linux kernel and designed primarily for touchscreen mobile devices such as smartphones and tablets.")
            } else {
                doesTTSSpeaking = false

                if (mediaPlayer != null) {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                    mediaPlayer = null
                }

                textToSpeechManager?.close()
            }
        }
    }

    private fun Context.hideKeyboard(view: View) {
        val inputMethodManager =
            getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
    }


    private fun initSpeechListener() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(speechListener)
    }

    private fun initTTS() {
        textToSpeechManager = TextToSpeechManager(this)
    }


    private fun initAI() {
        /*val dotenv = Dotenv.configure().directory("./assets").filename("keys/env").load()
        val apikey = dotenv.get("OPEN_AI_API_KEY")

        *//*****************************************************************************
         * W A R N I N G
         * TODO: Obfuscate before release to prevent leaks and surprise bills
         *****************************************************************************//*

        if (apikey != null) {
            ai = OpenAI(apikey)
        }*/

        if (key == null) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
        } else {
            ai = OpenAI(key!!)
        }
    }

    /** SYSTEM INITIALIZATION END **/

    private fun startRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

        recognizer?.startListening(intent)
    }

    private fun putMessage(message: String, isBot: Boolean) {
        val map: HashMap<String, Any> = HashMap()

        map["message"] = message
        map["isBot"] = isBot

        messages.add(map)
        adapter?.notifyDataSetChanged()

        chat?.post {
            chat?.setSelection(adapter?.count!! - 1)
        }

        saveSettings()
    }

    @OptIn(BetaOpenAI::class)
    private suspend fun generateResponse(request: String, shouldPronounce: Boolean) {
        val chatCompletionRequest = ChatCompletionRequest(
            model = ModelId("gpt-3.5-turbo"),
            messages = listOf(
                ChatMessage(
                    role = ChatRole.User,
                    content = request
                )
            )
        )

        try {
            val completion: ChatCompletion = ai!!.chatCompletion(chatCompletionRequest)

            val response = completion.choices[0].message?.content
            putMessage(response!!, true)

            if (shouldPronounce) {
                textToSpeech(response)
            }
        } catch (e: Exception) {
            putMessage(e.stackTraceToString(), true)
        }

        btnMicro?.isEnabled = true
        btnSend?.isEnabled = true
        progress?.visibility = View.GONE
    }

    private fun textToSpeech(text: String) {
        try {
            audioData = textToSpeechManager?.synthesizeSpeech(text)
            playAudioU(audioData!!)
        } catch (e: Exception) {
            putMessage(e.stackTraceToString(), true)
        }
    }


    private fun playAudioU(audioDataArray: ByteArray) {
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            setDataSource(createMediaDataSource(audioDataArray))
            prepare()
            start()

            setOnCompletionListener {
                release()
                audioData = null
                textToSpeechManager?.close()
            }
        }
    }

    private fun createMediaDataSource(audioData: ByteArray): MediaDataSource {
        return object : MediaDataSource() {
            override fun getSize(): Long = audioData.size.toLong()

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= audioData.size) {
                    return -1
                }
                val endPosition = minOf(position + size, audioData.size.toLong())
                System.arraycopy(audioData, position.toInt(), buffer, offset, (endPosition - position).toInt())
                return (endPosition - position).toInt()
            }

            override fun close() {}
        }
    }
}