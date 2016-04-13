(ns uk.org.potentialdifference.darknet.main
  (:require [neko.activity :refer [defactivity set-content-view!]]
            [neko.debug :refer [*a]]
            [neko.find-view :refer [find-view]]
            [neko.log :as log]
            [neko.resource :as res]
            [neko.threading :refer [on-ui]]
            [neko.log :as log]
            [neko.intent :as intent]
            [neko.notify :refer [toast]]
            [neko.ui :refer [make-ui]]
            [neko.ui.mapping :refer [defelement]]
            [uk.org.potentialdifference.darknet.config :refer [config]]
            [uk.org.potentialdifference.darknet.websocket :as websocket]
            [uk.org.potentialdifference.darknet.activity-helpers :as helper]
            [uk.org.potentialdifference.darknet.camera :as camera]
            [uk.org.potentialdifference.darknet.server :as server]
            [uk.org.potentialdifference.darknet.storage :as storage]
            [uk.org.potentialdifference.darknet.screen :as screen]
            [cheshire.core :refer [parse-string]]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str])
  (:import [android.app Activity]
           [android.widget Button]
           [android.graphics Color]
           [android.view View]
           [android.view ViewGroup]
           [android.view ViewGroup$LayoutParams]
           [android.view Gravity]
           [android.graphics BitmapFactory]
           [android.widget ImageView]
           [android.widget VideoView]
           [android.widget ImageView$ScaleType]
           [android.media MediaPlayer$OnCompletionListener]
           [android.widget LinearLayout$LayoutParams]
           [android.net Uri]
           [android.graphics Color]
           [android.view SurfaceHolder]
           [android.view SurfaceView]
           [android.util DisplayMetrics]
           [android.content Intent]
           [android.content Context]
           [com.michogarcia.mjpegview MjpegView]
           [com.michogarcia.mjpegview MjpegInputStream]
           [uk.org.potentialdifference.darknet StreamCameraDelegate]
           [com.foxdogstudios.peepers CameraStreamer]
           android.widget.EditText))

(res/import-all)

(defn parse-long [x]
  (Long/parseLong x))

(defn parse-vals [coll kvs]
  (reduce (fn [m [k v]]
            (cond-> m
              (contains? m k)
              (update-in [k] v))) coll kvs))

(defn ->instruction [str]
  (-> (parse-string str true)
      (parse-vals {:width parse-long
                   :height parse-long
                   :camera parse-long})))

(defn replace-view! [^Activity this ^View new]
  (when-let [^View container (find-view this ::container)]
    (let [^View old (.getChildAt  container 0)]
      (.removeView container old)
      (.addView container new 0))))

(defn create-mjpeg-view ^MjpegView [^Context context source-url width height]
  (let [^MjpegView mjpeg-view (new MjpegView context)]
    (future
      (let [stream (new MjpegInputStream (io/input-stream source-url))]
        (doto mjpeg-view
          ;; (.setResolution width height)
          (.setSource stream)
          (.setDisplayMode MjpegView/SIZE_FULLSCREEN))))
    mjpeg-view))

(defn stream-view [^Context activity instruction]
  (create-mjpeg-view activity
                     #_"http://webcam1.lpl.org/axis-cgi/mjpg/video.cgi"
                     (get instruction :from)
                     (get instruction :width)
                     (get instruction :height)))


(defn new-camera-streamer [^StreamCameraDelegate delegate
                           index port
                           ^SurfaceHolder holder
                           desired-width
                           desired-height]
  (let [preview-size-index (int 0)
        jpeg-quality (int 40)]
    (CameraStreamer.
     delegate
     (int index)
     false
     (int port)
     (int preview-size-index)
     (int jpeg-quality)
     holder
     desired-width
     desired-height)))

(defn fit-within [fit-w fit-h within-w within-h]
  (let [aspect-ratio (/ fit-w fit-h)
        within-ratio (/ within-w within-h)]
    (if (> aspect-ratio within-ratio)
      [within-w (int (/ within-w aspect-ratio))]
      [(int (* within-h aspect-ratio)) within-h])))

(defn fit-screen! [view activity width height]
  (let [^DisplayMetrics screen (.getDisplayMetrics (.getResources activity))
        ^ViewGroup$LayoutParams params (.getLayoutParams view)
        [fit-w fit-h] (fit-within width height
                                  (.-widthPixels screen)
                                  (.-heightPixels screen))]
    (set! (.-width params) (int fit-w))
    (set! (.-height params) (int fit-h))
    (on-ui
        (toast (format "Fit (%d x %d) to (%d x %d)"
                       width height
                       (int fit-w) (int fit-h)))
        (doto view
          (.setLayoutParams params)
          (.requestLayout)))))

(def camera-streamer
  (atom nil))

(defelement :surface-view
  :classname android.view.SurfaceView)

(defn create-preview-surface [^Context activity camera-index from to width height]
  (let [^SurfaceView surface (SurfaceView. activity)]
    (future
      (let [delegate (reify
                       android.view.SurfaceHolder$Callback
                       (surfaceChanged [this holder fmt width height])
                       (surfaceCreated [this holder]
                         (reset! camera-streamer (new-camera-streamer this camera-index  8085 holder width height))
                         (.start ^CameraStreamer @camera-streamer))
                       (surfaceDestroyed [this holder]
                         (.stop ^CameraStreamer @camera-streamer))

                       uk.org.potentialdifference.darknet.StreamCameraDelegate
                       (cameraStreamDidStart [this width height]
                         (let [^DisplayMetrics screen (.getDisplayMetrics (.getResources activity))
                               ^ViewGroup$LayoutParams params (.getLayoutParams surface)
                               [fit-w fit-h] (fit-within width height
                                                         (.-widthPixels screen)
                                                         (.-heightPixels screen))]
                           (set! (.-width params) (int fit-w))
                           (set! (.-height params) (int fit-h))
                           (on-ui
                               (toast (format "Fit (%d x %d) to (%d x %d)"
                                              width height
                                              (int fit-w) (int fit-h)))
                               (doto surface
                                 (.setLayoutParams params)
                                 (.requestLayout)))
                           (when (and from to)
                             (log/i "darknet" "stream-video" from to width height)
                             (server/stream-video from to width height)))))]
        (doto (.getHolder surface)
          (.setType SurfaceHolder/SURFACE_TYPE_PUSH_BUFFERS)
          (.addCallback delegate))))
    surface))

(defn camera-view [activity instruction]
  (create-preview-surface activity
                          (get instruction :camera)
                          (get instruction :from)
                          (get instruction :to)
                          (get instruction :width)
                          (get instruction :height)))

(defn fit-linear-layout! [view]
  (let [params (LinearLayout$LayoutParams.
                LinearLayout$LayoutParams/MATCH_PARENT
                LinearLayout$LayoutParams/MATCH_PARENT)]
    (set! (.-gravity params) Gravity/CENTER)
    (set! (.-weight params) (float 1.0))
    (doto view
      (.setLayoutParams params))))

(defn image-from-url [activity url]
  (let [view (ImageView. activity)]
    (fit-linear-layout! view)
    (let [f (fn [bytes]
              (let [bitmap (BitmapFactory/decodeByteArray bytes
                                                          0
                                                          (count bytes))]
                (on-ui
                    (.setImageBitmap view bitmap))))]
      (server/get-bytes url f))
    view))

(defn image-from-path [activity path]
  (doto (ImageView. activity)
    (fit-linear-layout!)
    (.setImageBitmap (BitmapFactory/decodeFile path))))

(defn layout [activity view]
  (make-ui activity
           [:linear-layout {:background-color Color/BLACK
                            :layout-width :fill
                            :layout-height :fill
                            :gravity Gravity/CENTER}
            view]))

(defn setVideoSource [video source]
  (log/i "darknet setting video source" source)
  (if (.startsWith source "http")
    (.setVideoURI video (Uri/parse source))
    (.setVideoPath video source)))

(defn swap-view! [activity view]
  (replace-view! activity view))

(defn idle-screen [color]
  [:relative-layout {:layout-width :fill
                     :layout-height :fill}
   [:text-view {:text " °"
                :id ::status-indicator
                :text-size 50
                :text-color color}]])

(defn setup-video-view [view activity path]
  (doto view
    (fit-linear-layout!)
    (setVideoSource path)
    (.setOnCompletionListener
     (reify
       MediaPlayer$OnCompletionListener
       (onCompletion [this mp]
         (swap-view! activity (layout activity (idle-screen Color/GREEN))))))
    (.start))
  view)

(defn video-from-path [activity path]
  (setup-video-view (VideoView. activity) activity path))

(defn save-locally! [activity instruction]
  (when-let [name (:name instruction)]
    (when-let [url (:url instruction)]
      (log/i "darknet" "getting bytes...")
      (server/get-bytes url
                        (fn [bytes]
                          (log/i "darknet" "writing bytes...")
                          (storage/write-bytes! bytes name)
                          (on-ui
                              (toast (str "Saved " name))))))))

(defn view-local [activity instruction]
  (when-let [name (:name instruction)]
    (when-let [path (storage/local-path name)]
      (case (:type instruction)
        "image" (layout activity (image-from-path activity path))
        "video" (layout activity (video-from-path activity path))))))

(defn view-remote [activity instruction]
  (when-let [url (:url instruction)]
    (case (:type instruction)
      "image" (layout activity (image-from-url activity url))
      "video" (layout activity (video-from-path activity url)))))

(defn set-status! [context color]
  (on-ui
      (if-let [indicator (find-view context ::status-indicator)]
        (.setTextColor indicator color))))

(def client
  (atom nil))

(defactivity uk.org.potentialdifference.darknet.MainActivity
  :key :main
  :features [:no-title]
  
  (onCreate [^Activity this bundle]
    (.superOnCreate this bundle)
    (helper/fullscreen! this)
    (helper/keep-screen-on! this)
    (helper/landscape! this)
    (on-ui
        (set-content-view! this
          [:linear-layout {:id ::container
                           :background-color Color/BLACK
                           :gravity Gravity/CENTER
                           :orientation :vertical
                           :layout-width :fill
                           :layout-height :fill}
           (idle-screen Color/RED)])))
  (onNewIntent [this intent]
               (.superOnNewIntent this intent)
               (log/i "darknet on new intent"))
  (onStart [this]
           (.superOnStart this)
           (let [sizes {:screen (screen/dimensions this)
                        :rear  (camera/preview-sizes 0)
                        :front (camera/preview-sizes 1)}
                 on-message (fn [str]
                              (let [instruction (->instruction str)
                                    sv (fn [view] ;; Menononic: swap-view!
                                         (swap-view! this view))]
                                (log/i "darknet" instruction)
                                (on-ui
                                    (case (:message instruction)
                                      "streamCamera" (sv (camera-view this instruction))
                                      "viewStream" (sv (stream-view this instruction))
                                      "saveLocally" (save-locally! this instruction)
                                      "viewRemote" (sv (view-remote this instruction))
                                      "viewLocal" (sv (view-local this instruction))
                                      "info" (sv (layout this [:text-view {:text
                                                                           (let [out (java.io.StringWriter.)]
                                                                             (pprint sizes out)
                                                                             (.toString out))}]))
                                      "stop" (sv (layout this (idle-screen Color/GREEN)))
                                      :default))))]
             (letfn [(new-client []
                       (log/i "darknet" "new client called")
                       (reset! client
                               (websocket/connect!
                                (:ws-url config)
                                {:on-open (fn [_]
                                            (log/i "darknet" "websocket open")
                                            (set-status! this Color/GREEN))
                                 :on-close (fn [code reason remote]
                                             (log/i "darknet on close" code reason remote)
                                             (when-not (nil? @client)
                                               (log/i "darknet unexpected close ... restarting")
                                               (set-status! this Color/RED)
                                               (Thread/sleep 1000)
                                               (new-client)))
                                 :on-message on-message
                                 :on-error (fn [e]
                                             (log/i "darknet on error" (.getMessage e))
                                             (set-status! this Color/RED))})))]
               (new-client)))
           (log/i "darknet on start"))
  (onResume [this]
            (.superOnResume this)
            (log/i "darknet on resume"))
  (onPause [this]
           (.superOnPause this)
           (log/i "darknet on pause"))
  (onStop [this]
          (.superOnStop this)
          (when-let [c @client]
            (log/i "darknet close client")
            (reset! client nil)
            (.closeBlocking c))
          (log/i "darknet on stop"))
  (onDestroy [this]
             (.superOnDestroy this)
             (log/i "darknet on destroy")))

