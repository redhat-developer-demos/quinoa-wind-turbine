import Shake from './Shake';
import { ENABLE_BLOWING } from '../Config';

let analyserNode;
let pcmData;

export async function startVolumeMeter() {
  if (!navigator.mediaDevices) {
    throw Error('navigator.mediaDevices is not available');
  }
  console.log('Volume Meter enabled');
  const stream = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
  const audioContext = new AudioContext();
  const mediaStreamAudioSourceNode = audioContext.createMediaStreamSource(stream);
  analyserNode = audioContext.createAnalyser();
  mediaStreamAudioSourceNode.connect(analyserNode);
  pcmData = new Float32Array(analyserNode.fftSize);
}

if (ENABLE_BLOWING) {
  startVolumeMeter().catch((e) => console.error(e));
}

export function getVolume() {
  if (!pcmData) {
    return undefined;
  }
  analyserNode.getFloatTimeDomainData(pcmData);
  let sumSquares = 0.0;
  for (const amplitude of pcmData) { sumSquares += amplitude * amplitude; }
  const number = Math.sqrt(sumSquares / pcmData.length);
  const amplified = Math.round(number * 200);
  return Math.min(100, amplified);
}

export function startSwipeSensor(handler) {
  let startX = 0;
  let startY = 0;

  document.addEventListener('touchstart', (e) => {
    startX = e.changedTouches[0].screenX;
    startY = e.changedTouches[0].screenY;
  });

  document.addEventListener('touchend', (e) => {
    const xDiff = startX - e.changedTouches[0].screenX;
    const yDiff = startY - e.changedTouches[0].screenY;
    if (Math.abs(xDiff) > Math.abs(yDiff)) { /* most significant */
      if (xDiff > 0) {
        /* right swipe */
        handler('right', xDiff);
      } else {
        /* left swipe */
        handler('left', xDiff);
      }
    } else if (yDiff > 0) {
      /* down swipe */
      handler('down', yDiff);
    } else {
      /* up swipe */
      handler('up', yDiff);
    }
  });
}

let shake;

export function enableShakeSensor() {
  shake = new Shake();
}

export function startShakeSensor(handler) {
  shake.start(handler);
  return () => shake.stop();
}
