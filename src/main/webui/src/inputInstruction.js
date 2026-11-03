// The explicit .js extension keeps this module loadable by the Node test runner, which resolves
// imports strictly; Vite accepts it too.
import { ENABLE_BLOWING, ENABLE_SWIPING } from './Config.js';
import { inputsForMode } from './inputMode.js';

const ACTIONS = [
  ['tap', 'tap the windmill'],
  ['shake', 'shake your phone'],
  ['blow', 'blow into your phone'],
  ['swipe', 'swipe the screen'],
];

// Blowing and swiping are not switchable at runtime; Config.js decides them.
const DEFAULT_EXTRA_INPUTS = {
  blow: ENABLE_BLOWING,
  swipe: ENABLE_SWIPING,
};

/**
 * What the players should do once the race starts, from the live input mode ('tap', 'shake' or
 * 'both') plus the blow and swipe flags in Config.js: "Tap the windmill now", "Tap the windmill
 * or shake your phone now". Both the dashboard banner and the phone's start flash show it, so
 * they can never disagree with what the phone accepts.
 */
export function inputInstruction(mode, extraInputs = DEFAULT_EXTRA_INPUTS) {
  const inputs = { ...inputsForMode(mode), ...extraInputs };
  const actions = ACTIONS.filter(([key]) => inputs[key]).map(([, text]) => text);
  if (actions.length === 0) {
    return 'Go';
  }
  const phrase = actions.length === 1
    ? actions[0]
    : `${actions.slice(0, -1).join(', ')} or ${actions[actions.length - 1]}`;
  return `${phrase[0].toUpperCase()}${phrase.slice(1)} now`;
}
