import React, { useEffect, useState } from 'react';
import styled from 'styled-components';
import { themeApi } from '../../api';
import { useTheme } from '../../ThemeContext';

/**
 * The operator's theme control, shown between races: apply a preset, edit the team names and
 * colors by hand, or (when the server runs with AI) ask a model for names or a full theme.
 * Every switch happens server-side and arrives back as a theme event, so this panel never
 * restyles anything locally and every dashboard and phone switches together.
 */
export default function ThemePanel() {
  const theme = useTheme();
  const [open, setOpen] = useState(false);
  const [presets, setPresets] = useState([]);
  const [names, setNames] = useState(['', '']);
  const [colors, setColors] = useState(['', '']);
  const [prompt, setPrompt] = useState('');
  const [message, setMessage] = useState(null);
  const [aiEnabled, setAiEnabled] = useState(false);
  const [busy, setBusy] = useState(false);

  // The server's AI flag and the preset list are only needed once the panel opens.
  useEffect(() => {
    if (!open || presets.length > 0) {
      return;
    }
    themeApi.fetchPresets().then(setPresets);
    fetch('/api/config')
      .then((r) => r.json())
      .then((config) => setAiEnabled(!!config.aiEnabled))
      .catch(() => {});
  }, [open, presets.length]);

  // Reseed the editors whenever the live theme changes, so applying a preset updates the fields.
  useEffect(() => {
    const teams = [...(theme.teams || [])].sort((a, b) => a.id - b.id);
    setNames(teams.map((t) => t.name));
    setColors(teams.map((t) => t.color));
  }, [theme]);

  function report(result, success) {
    if (result.ok) {
      setMessage({ ok: true, text: success });
    } else {
      setMessage({ ok: false, text: result.errors.join('. ') || 'The theme was not applied' });
    }
  }

  async function run(action, success) {
    setBusy(true);
    setMessage(null);
    try {
      report(await action(), success);
    } catch {
      setMessage({ ok: false, text: 'The server is unreachable' });
    } finally {
      setBusy(false);
    }
  }

  const applyCustom = () => run(() => themeApi.applyTheme({
    id: 'custom',
    name: 'Custom',
    // Cars, accents and logos come from the live theme; only the names and colors are edited.
    teams: [...theme.teams].sort((a, b) => a.id - b.id).map((t, i) => ({
      id: t.id, name: names[i], color: colors[i], car: t.car, accent: t.accent, logo: t.logo,
    })),
  }), 'Theme applied');

  const suggestNames = () => run(() => themeApi.suggestNames(prompt), 'Team names applied');

  const generate = () => run(() => themeApi.generateTheme(prompt), 'Theme generated and applied');

  if (!open) {
    return <Toggle data-testid="theme-toggle" onClick={() => setOpen(true)}>Theme</Toggle>;
  }
  return (
    <Panel data-testid="theme-panel">
      <Header>
        <span>Theme</span>
        <button type="button" onClick={() => setOpen(false)} aria-label="Close theme panel">✕</button>
      </Header>

      <Section title="Presets">
        {presets.map((preset) => (
          <PresetButton
            key={preset.id}
            type="button"
            disabled={busy}
            onClick={() => run(() => themeApi.applyPreset(preset.id), `${preset.name} applied`)}
          >
            {preset.name}
          </PresetButton>
        ))}
      </Section>

      <Section title="Teams">
        {[0, 1].map((i) => (
          <TeamRow key={i}>
            <input
              aria-label={`Team ${i + 1} name`}
              value={names[i] || ''}
              maxLength={24}
              onChange={(e) => setNames(names.map((n, j) => (j === i ? e.target.value : n)))}
            />
            <input
              aria-label={`Team ${i + 1} color`}
              value={colors[i] || ''}
              onChange={(e) => setColors(colors.map((c, j) => (j === i ? e.target.value : c)))}
            />
            <span className="swatch" style={{ backgroundColor: colors[i] }} />
          </TeamRow>
        ))}
        <button type="button" className="action" disabled={busy} onClick={applyCustom}>Apply teams</button>
      </Section>

      {aiEnabled && (
        <Section title="AI">
          <input
            aria-label="Theme prompt"
            placeholder="Event, location or audience…"
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
          />
          <div className="row">
            <button type="button" className="action" disabled={busy || !prompt} onClick={suggestNames}>
              Suggest names
            </button>
            <button type="button" className="action" disabled={busy || !prompt} onClick={generate}>
              Generate theme
            </button>
          </div>
        </Section>
      )}

      {message && <Message ok={message.ok}>{message.text}</Message>}
    </Panel>
  );
}

const Toggle = styled.button`
  position: fixed;
  right: 18px;
  bottom: 18px;
  z-index: 30;
  padding: 8px 18px;
  border: 1px solid #444;
  border-radius: 6px;
  background: #1f1f1f;
  color: #f2f2f2;
  font-size: 1rem;
  cursor: pointer;
`;

const Panel = styled.div`
  position: fixed;
  right: 18px;
  top: 70px;
  bottom: 70px;
  width: 280px;
  z-index: 30;
  box-sizing: border-box;
  padding: 14px 16px;
  overflow-y: auto;
  background: #1f1f1f;
  border: 1px solid #333;
  border-radius: 10px;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.45);
  color: #f2f2f2;
  display: flex;
  flex-direction: column;
  gap: 14px;
`;

const Header = styled.div`
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 1.2rem;
  font-weight: 700;

  button {
    border: 0;
    background: transparent;
    color: #9ca3af;
    font-size: 1rem;
    cursor: pointer;
  }
`;

function Section({ title, children }) {
  return (
    <section>
      <h3>{title}</h3>
      {children}
    </section>
  );
}

const PresetButton = styled.button`
  display: block;
  width: 100%;
  margin: 4px 0;
  padding: 7px 10px;
  border: 1px solid #3a3a3a;
  border-radius: 6px;
  background: #262626;
  color: #f2f2f2;
  text-align: left;
  cursor: pointer;

  &:hover {
    border-color: #666;
  }

  &:disabled {
    opacity: 0.5;
    cursor: wait;
  }
`;

const TeamRow = styled.div`
  display: flex;
  gap: 6px;
  align-items: center;
  margin: 4px 0;

  input {
    box-sizing: border-box;
    padding: 5px 8px;
    border: 1px solid #3a3a3a;
    border-radius: 6px;
    background: #262626;
    color: #f2f2f2;
  }

  input[aria-label*='name'] {
    flex: 1 1 auto;
    min-width: 0;
  }

  input[aria-label*='color'] {
    width: 84px;
  }

  .swatch {
    flex: none;
    width: 18px;
    height: 18px;
    border-radius: 4px;
    border: 1px solid #555;
  }
`;

const Message = styled.div`
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 0.9rem;
  ${(props) => (props.ok
    ? 'background: rgba(46, 125, 50, 0.25); border: 1px solid #2e7d32;'
    : 'background: rgba(198, 40, 40, 0.25); border: 1px solid #c62828;')}
`;
