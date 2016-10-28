package richard.example.com.continuouscaptureactivity;

/**
 * Created by Administrator on 2016/10/27.
 */

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

/**
 * Encodes video in a fixed-size circular buffer
 * The obvious way to do this would be to store each packet in its own buffer and hook it
 * into a linked list.  The trouble with this approach is that it requires constant
 * allocation, which means we'll be driving the GC to distraction as the frame rate and
 * bit rate increase.  Instead we create fixed-size pools for video data and metadata,
 * which requires a bit more work for us but avoids allocations in the steady state.
 * <p>
 * Video must always start with a sync frame (a/k/a key frame, a/k/a I-frame).  When the
 * circular buffer wraps around, we either need to delete all of the data between the frame at
 * the head of the list and the next sync frame, or have the file save function know that
 * it needs to scan forward for a sync frame before it can start saving data.
 * <p>
 * When we're told to save a snapshot, we create a MediaMuxer, write all the frames out,
 * and then go back to what we were doing.
 */
public class CircularEncoder {
    private static final String TAG = "CircularEncoder";

    private static final String MIME_TYPE = "video/avc";    //H.264
    private static final int IFRAME_INTERVAL = 1;

    private EncoderThread mEncoderThread;
    private Surface mInputSurface;
    private MediaCodec mEncoder;

    // Callback function definitions. CircularEncoder caller must provide one.
    public interface Callback {
        //called some time after saveVideo(), when all data has been written to the output file
        void fileSaveComplete(int status);

        //called occasionally.
        void bufferStatus(long totalTimeMsec);
    }

    //Configures encoder, and prepares the input Surface.

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    public CircularEncoder(int width, int height, int bitRate, int frameRate, int desiredSpanSec,
                           Callback cb) throws IOException
    {
            if(desiredSpanSec < IFRAME_INTERVAL * 2)
            {
                throw new RuntimeException("Requested time span is too short: " + desiredSpanSec +
                        " vs. " + (IFRAME_INTERVAL * 2));
            }
            CircularEncoderBuffer encBuffer = new CircularEncoderBuffer(bitRate, frameRate,
                    desiredSpanSec);

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE,width,height);

        //Set some properties. Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);

        //Create a MediaCodec encoder, and configure it with our format, Get a Surface
        //we can use for input and wrap it with a class that handles the EGL work.
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = mEncoder.createInputSurface();
        mEncoder.start();

        // Start the encoder thread last.
        mEncoderThread = new EncoderThread(mEncoder, encBuffer, cb);
        mEncoderThread.start();
        mEncoderThread.waitUntilReady();
    }

    //Returns the encorder's input surface.
    public Surface getInputSurface(){
        return mInputSurface;
    }

    //Shuts down the encorder thread, and release encoder resources.
    //Does not return until the encoder thread has stopped.
    public void shutdown() {
        Handler handler = mEncoderThread.getHandler();
        handler.sendMessage(handler.obtainMessage(EncoderThread.EncoderHandler.MSG_SHUTDOWN));
        try {
            mEncoderThread.join();
        } catch (InterruptedException ie) {
            Log.w(TAG, "Encoder thread join() was interrupted", ie);
        }

        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
    }

    void frameAvailableSoon(){
        Handler handler = mEncoderThread.getHandler();
        handler.sendMessage(handler.obtainMessage(
                EncoderThread.EncoderHandler.MSG_FRAME_AVAILABLE_SOON));
    }

    //save the encoder output to a .mp4 file
    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    void saveVideo(File outputFile){
       Handler handler = mEncoderThread.getHandler();
        handler.sendMessage(handler.obtainMessage(
                EncoderThread.EncoderHandler.MSG_SAVE_VIDEO, outputFile
        ));
    }


    //Object that encapsulates the encoder thread.
    private static class EncoderThread extends Thread {
        private MediaCodec mEncoder;
        private MediaFormat mEncodedFormat;
        private MediaCodec.BufferInfo mBufferInfo;

        private EncoderHandler mHandler;
        private CircularEncoderBuffer mEncBuffer;
        private CircularEncoder.Callback mCallback;
        private int mFrameNum;

        private final Object mLock = new Object();
        private volatile boolean mReady = false;

        @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN)
        public EncoderThread(MediaCodec mediaCodec, CircularEncoderBuffer encBuffer,
                             CircularEncoder.Callback callback){

                mEncoder = mediaCodec;
                mEncBuffer = encBuffer;
                mCallback = callback;

                mBufferInfo  = new MediaCodec.BufferInfo();
        }

        //Trhead entry point.
        @Override
        public void run(){
            Looper.prepare();
            mHandler = new EncoderHandler(this);    //must create on encoder thread
            Log.d(TAG, "encoder thread ready");
            synchronized (mLock){
                mReady = true;
                mLock.notify(); //signal waitUnitilReady()
            }

            Looper.loop();

            synchronized (mLock){
                mReady = false;
                mHandler = null;
            }
            Log.d(TAG, "looper quit");
        }

        //Waits until the encoder thread is ready to receive messages.
        public void waitUntilReady() {
            synchronized (mLock){
                while (!mReady){
                    try {
                        mLock.wait();
                    }catch (InterruptedException ie){

                    }
                }
            }
        }


        //Returns the Handler used to send message to the encoder thread.
        public EncoderHandler getHandler(){
            synchronized (mLock){
                //Confirm ready state.
                if(!mReady){
                    throw new RuntimeException("not ready");
                }
            }
            return mHandler;
        }

        //Drains all pending output from the decoder, and adds it to the circular buffer.

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        public void drainEncoder() {
            final int TIMEOUT_USEC = 0; //no timeout --- check for buffers, bail if none

            ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
            while (true){
                int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
                if(encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER){
                    //no output available yet
                    break;
                } else if(encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED){
                    //not expected for an encoder
                    encoderOutputBuffers = mEncoder.getOutputBuffers();
                } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    // Should happen before receiving buffers, and should only happen once.
                    mEncodedFormat = mEncoder.getOutputFormat();
                    Log.d(TAG,"encoder output format changed: " + mEncodedFormat);
                } else if (encoderStatus < 0){
                    Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                            encoderStatus);
                    //let's ignore it
                } else {
                    ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                    if(encodedData == null){
                        throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                                " was null");
                    }

                    if((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        //The codec config data was pulled out when we got the
                        // INFO_OUTPUT_FORMAT_CHANGED  status.
                        mBufferInfo.size = 0;
                    }

                    if(mBufferInfo.size != 0) {
                        //adjust the ByteBuffer values to match BufferInfo (not needed?)
                        encodedData.position(mBufferInfo.offset);
                        encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                        mEncBuffer.add(encodedData, mBufferInfo.flags,
                                mBufferInfo.presentationTimeUs);
                    }
                    mEncoder.releaseOutputBuffer(encoderStatus, false);

                    if((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0){
                        Log.w(TAG, "reached end of stream unexpectedly");
                        break;  //out of while
                    }
                }
            }
        }

        /**
         * Drains the encoder output.
         * <p>
         * See notes for {@link CircularEncoder#frameAvailableSoon()}.
         */
        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        void frameAvailableSoon() {

            drainEncoder();

            mFrameNum++;
            if ((mFrameNum % 10) == 0) {        // TODO: should base off frame rate or clock?
                mCallback.bufferStatus(mEncBuffer.computeTimeSpanUsec());
            }
        }


        @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
        void saveVideo(File outputFile) {


            int index = mEncBuffer.getFirstIndex();
            if (index < 0) {
                Log.w(TAG, "Unable to get first index");
                mCallback.fileSaveComplete(1);
                return;
            }

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaMuxer muxer = null;
            int result = -1;
            try {
                muxer = new MediaMuxer(outputFile.getPath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                int videoTrack = muxer.addTrack(mEncodedFormat);
                muxer.start();

                do {
                    ByteBuffer buf = mEncBuffer.getChunk(index, info);

                    muxer.writeSampleData(videoTrack, buf, info);
                    index = mEncBuffer.getNextIndex(index);
                } while (index >= 0);
                result = 0;
            } catch (IOException ioe) {
                Log.w(TAG, "muxer failed", ioe);
                result = 2;
            } finally {
                if (muxer != null) {
                    muxer.stop();
                    muxer.release();
                }

                mCallback.fileSaveComplete(result);
            }
        }


        //Tells the Looper to quit.
        void shutdown(){
            Looper.myLooper().quit();
        }


        /**
        * Handler for EncoderThread.  Used for messages sent from the UI thread (or whatever
        * is driving the encoder) to the encoder thread.
        * <p>
        * The object is created on the encoder thread.
        */
        private static class EncoderHandler extends Handler {
            public static final int MSG_FRAME_AVAILABLE_SOON = 1;
            public static final int MSG_SAVE_VIDEO = 2;
            public static final int MSG_SHUTDOWN = 3;

        // This shouldn't need to be a weak ref, since we'll go away when the looper quits.
        // but no real harm in it.
        private WeakReference<EncoderThread> mWeakEncoderThread;

        //constructor
        public EncoderHandler(EncoderThread et) {
            mWeakEncoderThread = new WeakReference<EncoderThread>(et);
        }

        @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
        @Override //runs on encoder thread
        public void handleMessage(Message msg){
            int what = msg.what;

            EncoderThread encoderThread  = mWeakEncoderThread.get();
            if(encoderThread == null) {
                Log.w(TAG, "EncoderHandler.handleMessage: weak ref is null");
                return;
            }

            switch (what) {
                case MSG_FRAME_AVAILABLE_SOON:
                    encoderThread.frameAvailableSoon();
                    break;
                case MSG_SAVE_VIDEO:
                    encoderThread.saveVideo((File) msg.obj);
                    break;
                case MSG_SHUTDOWN:
                    encoderThread.shutdown();
                    break;
                default:
                    throw new RuntimeException("unknown message " + what);
            }
        }
        }
    }

}
