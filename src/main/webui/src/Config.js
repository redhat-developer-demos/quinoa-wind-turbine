export const DEV_MODE = !process.env.NODE_ENV || process.env.NODE_ENV === 'development';

export const IS_TOUCH_DEVICE = (('ontouchstart' in window)
    || (navigator.maxTouchPoints > 0)
    || (navigator.msMaxTouchPoints > 0));

export const TEAM_COLORS = ['skyblue', 'orange'];
export const TEAM_NAMES = ['Beach', 'Desert'];
export const TEAM_CARS = ["car-blue-convertible", "car-orange", "car-classic-black", "car-barbie", "car-grey", "car-red"]; // only the first 2 are used, the other ones are there for reference

// Dashboard
export const TAP_POWER = 15; // in MW
export const NB_TAP_NEEDED_PER_USER = DEV_MODE ? 10 : 150;
export const SHOW_TOP = 5;

// Mobile app
export const ENABLE_TAPPING = true;
export const ENABLE_SHAKING = false; // "false" in v1, set to "true" in v2
export const ENABLE_BLOWING = false;
export const ENABLE_SWIPING = false;


// LOGGING
console.log(`devMode: ${DEV_MODE}`);
console.log(`Tap needed: ${NB_TAP_NEEDED_PER_USER}`);
console.log('Swiping Sensor: ', ENABLE_SWIPING);
console.log('Tapping Sensor: ', ENABLE_TAPPING);
console.log('Blowing Sensor: ', ENABLE_BLOWING);
console.log('Shaking Sensor: ', ENABLE_SHAKING);

