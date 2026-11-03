// The player input modes the server can switch between (`inputMode` in /api/config). The explicit
// .js extension on imports of this module keeps it loadable by the Node test runner.
export const DEFAULT_INPUT_MODE = 'tap';

const INPUTS_BY_MODE = {
  tap: { tap: true, shake: false },
  shake: { tap: false, shake: true },
  both: { tap: true, shake: true },
};

/** The mode itself when it is a known one, otherwise the default. */
export function normalizeInputMode(mode) {
  return Object.hasOwn(INPUTS_BY_MODE, mode) ? mode : DEFAULT_INPUT_MODE;
}

/** Which of tap and shake a mode enables: `{ tap, shake }`. Unknown modes act as the default. */
export function inputsForMode(mode) {
  return { ...INPUTS_BY_MODE[normalizeInputMode(mode)] };
}

/** Whether the environment supports motion events. Pass a mock global to test. */
export function isDeviceMotionSupported(globalObj) {
  const target = globalObj !== undefined ? globalObj : (typeof window !== 'undefined' ? window : undefined);
  return typeof target !== 'undefined' && typeof target.DeviceMotionEvent !== 'undefined';
}

/**
 * Which inputs are usable on this device given the mode and motion sensor availability.
 * If DeviceMotionEvent is undefined, shaking cannot work so tapping is enabled in all modes
 * and shaking is disabled (fallback so no player is locked out).
 */
export function effectiveInputs(mode, hasMotion = isDeviceMotionSupported()) {
  const inputs = inputsForMode(mode);
  if (!hasMotion) {
    return { tap: true, shake: false };
  }
  return inputs;
}
