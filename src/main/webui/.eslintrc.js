module.exports = {
  env: {
    browser: true,
    es2021: true,
  },
  extends: [
    'plugin:react/recommended',
    'airbnb',
  ],
  overrides: [
  ],
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
  },
  plugins: [
    'react',
  ],
  rules: {
    'react/destructuring-assignment': 'off',
    'max-len': 'off',
    'react/prop-types': 'off',
    'no-restricted-syntax': 'off',
    'guard-for-in': 'off',
    'no-console': 'off',
    'no-prototypes-builtins': 'off',
    'no-restricted-exports': 'off',
    'jsx-a11y/control-has-associated-label': 'off',
    'react/button-has-type': 'off',
    'react/jsx-props-no-spreading': 'off',
    'jsx-a11y/alt-text': 'off',
    'react/jsx-no-bind': 'off',
    'no-fallthrough': 'off',
  },
};
