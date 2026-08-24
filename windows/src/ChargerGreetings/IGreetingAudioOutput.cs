// IGreetingAudioOutput.cs
//
// The seam between "decide whether to greet" and "make a noise". Keeping the
// controller free of any direct dependency on winmm means the decision rules
// (debounce, cooldown, sleep suppression) can be exercised in full without a
// sound card, and it leaves room for a different backend later without
// touching the logic that matters.

using System;

namespace ChargerGreetings
{
    internal interface IGreetingAudioOutput : IDisposable
    {
        /// <summary>
        /// Starts playback and returns immediately. Any greeting already in
        /// progress is replaced.
        /// </summary>
        /// <param name="onFinished">
        /// Called when playback ends: null on success, otherwise a message that
        /// can be shown to the user as-is.
        /// </param>
        void Play(WavFile clip, int volumePercent, Action<string> onFinished);

        /// <summary>Stops any greeting currently playing.</summary>
        void Stop();
    }
}
