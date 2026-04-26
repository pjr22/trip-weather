/**
 * Voice Guide
 * Thin wrapper over the Web Speech API.
 *
 * iOS Safari requires a user-gesture-triggered call to unlock TTS for the session
 * — call unlock() from the same click handler that starts navigation. Some mobile
 * browsers also drop later utterances if a queue grows past ~15s, so each say()
 * cancels any in-flight utterance rather than queueing.
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Nav = window.TripWeather.Nav || {};

window.TripWeather.Nav.VoiceGuide = {

    voice: null,
    enabled: true,

    isSupported: function() {
        return 'speechSynthesis' in window;
    },

    initialize: function() {
        if (!this.isSupported()) return;

        this._chooseVoice();
        // Voices may load asynchronously — repeat the selection once they arrive.
        if (window.speechSynthesis.getVoices().length === 0) {
            const handler = this._chooseVoice.bind(this);
            window.speechSynthesis.addEventListener('voiceschanged', handler, { once: true });
        }
    },

    _chooseVoice: function() {
        const voices = window.speechSynthesis.getVoices();
        const lang = window.TripWeather.Nav.Constants.VOICE_LANG;
        this.voice = voices.find(function(v) { return v.lang === lang; })
            || voices.find(function(v) { return v.lang && v.lang.startsWith('en'); })
            || null;
    },

    /**
     * Unlock TTS in the current page session — must be called from a user-gesture
     * handler (Safari otherwise rejects the first speak() call). A near-silent
     * utterance is enough.
     */
    unlock: function() {
        if (!this.isSupported()) return;
        const u = new SpeechSynthesisUtterance(' ');
        u.volume = 0;
        window.speechSynthesis.speak(u);
    },

    say: function(text) {
        if (!this.enabled || !this.isSupported() || !text) return;

        // Cancel any in-flight utterance — we'd rather speak the latest instruction
        // than backlog stale ones, especially around a missed turn or re-route.
        window.speechSynthesis.cancel();

        const u = new SpeechSynthesisUtterance(text);
        u.lang = window.TripWeather.Nav.Constants.VOICE_LANG;
        u.rate = window.TripWeather.Nav.Constants.VOICE_RATE;
        if (this.voice) u.voice = this.voice;
        window.speechSynthesis.speak(u);
    },

    cancel: function() {
        if (this.isSupported()) {
            window.speechSynthesis.cancel();
        }
    },

    setEnabled: function(enabled) {
        this.enabled = enabled;
        if (!enabled) this.cancel();
    }
};
