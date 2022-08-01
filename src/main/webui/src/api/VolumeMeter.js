let analyserNode;
let pcmData;

export async function startVolumeMeter() {
    if (!navigator.mediaDevices) {
        throw Error('navigator.mediaDevices is not available');
    }
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
    const audioContext = new AudioContext();
    const mediaStreamAudioSourceNode = audioContext.createMediaStreamSource(stream);
    analyserNode = audioContext.createAnalyser();
    mediaStreamAudioSourceNode.connect(analyserNode);
    pcmData = new Float32Array(analyserNode.fftSize);
}

export function getVolume() {
    if (!pcmData) {
        return undefined;
    }
    analyserNode.getFloatTimeDomainData(pcmData);
    let sumSquares = 0.0;
    for (const amplitude of pcmData) { sumSquares += amplitude*amplitude; }
    const number = Math.sqrt(sumSquares / pcmData.length);
    const amplified = Math.round(number * 200)
    return Math.min(100, amplified);
}