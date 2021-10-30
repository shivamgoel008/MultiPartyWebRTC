package com.jmm.brsap.multipartywebrtc

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.PeerConnection.IceServer
import java.util.ArrayList
import java.util.HashMap

class MainActivity : AppCompatActivity(), SignalingClient.Callback {

    private lateinit var eglBaseContext: EglBase.Context
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var localView: SurfaceViewRenderer
    private lateinit var mediaStream: MediaStream
    private lateinit var iceServers: List<IceServer>
    private lateinit var peerConnectionMap: HashMap<String, PeerConnection>
    private lateinit var remoteViews: Array<SurfaceViewRenderer>
    var remoteViewsIndex = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        peerConnectionMap = HashMap()
        /*iceServers = ArrayList()
        iceServers.add(IceServer.builder("stun:stun.l.google.com:19302").createIceServer())*/

        iceServers = listOf(
            IceServer.builder("3.109.143.89:3478")
                .setUsername("Admin ")
                .setPassword("password")
                .createIceServer()
        )
        eglBaseContext = EglBase.create().eglBaseContext

        // create PeerConnectionFactory
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(this)
                .createInitializationOptions()
        )
        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(eglBaseContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBaseContext)
        // create VideoCapturer
        val videoCapturer = createCameraCapturer(true)
        val videoSource = peerConnectionFactory.createVideoSource(
            videoCapturer!!.isScreencast
        )
        videoCapturer.initialize(
            surfaceTextureHelper,
            applicationContext,
            videoSource.capturerObserver
        )
        videoCapturer.startCapture(480, 640, 30)
        localView = findViewById(R.id.localView)
        localView.setMirror(true)
        localView.init(eglBaseContext, null)

        // create VideoTrack
        val videoTrack = peerConnectionFactory.createVideoTrack("100", videoSource)
        //        // display in localView
        videoTrack.addSink(localView)
        remoteViews = arrayOf(
            findViewById(R.id.remoteView),
            findViewById(R.id.remoteView2),
            findViewById(R.id.remoteView3)
        )
        for (remoteView in remoteViews) {
            remoteView.setMirror(false)
            remoteView.init(eglBaseContext, null)
        }
        mediaStream = peerConnectionFactory.createLocalMediaStream("mediaStream")
        mediaStream.addTrack(videoTrack)
        SignalingClient.get()?.init(this)
    }

    @Synchronized
    private fun getOrCreatePeerConnection(socketId: String): PeerConnection {
        var peerConnection = peerConnectionMap[socketId]
        if (peerConnection != null) {
            return peerConnection
        }
        peerConnection =
            peerConnectionFactory.createPeerConnection(iceServers, object : PeerConnectionAdapter(
                "PC:$socketId"
            ) {
                override fun onIceCandidate(iceCandidate: IceCandidate) {
                    super.onIceCandidate(iceCandidate)
                    SignalingClient.get()?.sendIceCandidate(iceCandidate, socketId)
                }

                override fun onAddStream(mediaStream: MediaStream) {
                    super.onAddStream(mediaStream)
                    val remoteVideoTrack = mediaStream.videoTracks[0]
                    runOnUiThread {
                        remoteVideoTrack.addSink(
                            remoteViews[remoteViewsIndex++]
                        )
                    }
                }
            })
        peerConnection!!.addStream(mediaStream)
        peerConnectionMap[socketId] = peerConnection
        return peerConnection
    }

    override fun onCreateRoom() {}
    override fun onPeerJoined(socketId: String) {
        val peerConnection = getOrCreatePeerConnection(socketId)
        peerConnection.createOffer(object : SdpAdapter("createOfferSdp:$socketId") {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                super.onCreateSuccess(sessionDescription)
                peerConnection.setLocalDescription(
                    SdpAdapter("setLocalSdp:$socketId"),
                    sessionDescription
                )
                SignalingClient.get()?.sendSessionDescription(sessionDescription, socketId)
            }
        }, MediaConstraints())
    }

    override fun onSelfJoined() {}
    override fun onPeerLeave(msg: String) {

    }


    override fun onOfferReceived(data: JSONObject) {
        runOnUiThread {
            val socketId = data.optString("from")
            val peerConnection = getOrCreatePeerConnection(socketId)
            peerConnection.setRemoteDescription(
                SdpAdapter("setRemoteSdp:$socketId"),
                SessionDescription(SessionDescription.Type.OFFER, data.optString("sdp"))
            )
            peerConnection.createAnswer(object : SdpAdapter("localAnswerSdp") {
                override fun onCreateSuccess(sessionDescription: SessionDescription) {
                    super.onCreateSuccess(sessionDescription)
                    peerConnectionMap[socketId]!!
                        .setLocalDescription(SdpAdapter("setLocalSdp:$socketId"), sessionDescription)
                    SignalingClient.get()?.sendSessionDescription(sessionDescription, socketId)
                }
            }, MediaConstraints())
        }
    }

    override fun onAnswerReceived(data: JSONObject) {
        val socketId = data.optString("from")
        val peerConnection = getOrCreatePeerConnection(socketId)
        peerConnection.setRemoteDescription(
            SdpAdapter("setRemoteSdp:$socketId"),
            SessionDescription(SessionDescription.Type.ANSWER, data.optString("sdp"))
        )
    }

    override fun onIceCandidateReceived(data: JSONObject) {
        val socketId = data.optString("from")
        val peerConnection = getOrCreatePeerConnection(socketId)
        peerConnection.addIceCandidate(
            IceCandidate(
                data.optString("id"),
                data.optInt("label"),
                data.optString("candidate")
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        SignalingClient.get()?.destroy()
    }

    private fun createCameraCapturer(isFront: Boolean): VideoCapturer? {
        val enumerator = Camera1Enumerator(false)
        val deviceNames = enumerator.deviceNames

        // First, try to find front facing camera
        for (deviceName in deviceNames) {
            if (if (isFront) enumerator.isFrontFacing(deviceName) else enumerator.isBackFacing(
                    deviceName
                )
            ) {
                val videoCapturer: VideoCapturer? = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }
        return null
    }
}
