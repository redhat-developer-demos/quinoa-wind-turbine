export const DEV_MODE = !process.env.NODE_ENV || process.env.NODE_ENV === 'development';

export const IS_TOUCH_DEVICE = (('ontouchstart' in window)
    || (navigator.maxTouchPoints > 0)
    || (navigator.msMaxTouchPoints > 0));

// available cars images: 'car-classic-black', 'car-barbie', 'car-grey', 'car-red', 'car-blue-convertible', 'car-orange'
export const TEAMS_CONFIG = [
  {
    name: 'Mountains',
    color: 'grey',
    car: 'car-orange',
  },
  {
    name: 'Beach',
    color: 'skyblue',
    car: 'car-blue-convertible',
  },
];

// Dashboard
export const TAP_POWER = 15; // in MW
export const NB_TAP_NEEDED_PER_USER = 100;
export const SHOW_TOP = 5;

// Mobile app
export const ENABLE_TAPPING = true;
export const ENABLE_SHAKING = false; // 'false' in v1, set to 'true' in v2
export const ENABLE_BLOWING = false;
export const ENABLE_SWIPING = false;

// LOGGING
console.log(`devMode: ${DEV_MODE}`);
console.log(`Tap needed: ${NB_TAP_NEEDED_PER_USER}`);
console.log('Swiping Sensor: ', ENABLE_SWIPING);
console.log('Tapping Sensor: ', ENABLE_TAPPING);
console.log('Blowing Sensor: ', ENABLE_BLOWING);
console.log('Shaking Sensor: ', ENABLE_SHAKING);
