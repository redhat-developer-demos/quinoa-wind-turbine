import * as React from 'react';
import { INPUT_MODE } from './Config';
import { normalizeInputMode } from './inputMode';

const InputModeContext = React.createContext(null);

// Holds the live input mode ('tap', 'shake' or 'both'), seeded from the configuration that
// loadConfig() fetched before the app mounted. setInputMode is the seam the input-mode game event
// drives; it normalizes, so an unknown value becomes 'tap'.
export function InputModeProvider({ children }) {
  const [inputMode, setMode] = React.useState(() => normalizeInputMode(INPUT_MODE));
  const setInputMode = React.useCallback((mode) => setMode(normalizeInputMode(mode)), []);
  const value = React.useMemo(() => ({ inputMode, setInputMode }), [inputMode, setInputMode]);
  return <InputModeContext.Provider value={value}>{children}</InputModeContext.Provider>;
}

export function useInputMode() {
  const value = React.useContext(InputModeContext);
  if (!value) throw new Error('useInputMode must be used within an InputModeProvider');
  return value.inputMode;
}

export function useSetInputMode() {
  const value = React.useContext(InputModeContext);
  if (!value) throw new Error('useSetInputMode must be used within an InputModeProvider');
  return value.setInputMode;
}
