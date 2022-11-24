export const IS_TOUCH_DEVICE = (('ontouchstart' in window)
    || (navigator.maxTouchPoints > 0)
    || (navigator.msMaxTouchPoints > 0));

export const TEAM_COLORS = ['#6C84A3', 'orange'];

// Dashboard
export const TAP_POWER = 15; // in MW
export const NB_TAP_NEEDED_PER_USER = 150;
export const SHOW_TOP = 5;

// Mobile app
export const ENABLE_TAPPING = false;
export const ENABLE_SHAKING = true; // "false" in v1, set to "true" in v2
export const ENABLE_BLOWING = false;
export const ENABLE_SWIPING = false;

// LOGGING
console.log('Swiping Sensor: ', ENABLE_SWIPING);
console.log('Tapping Sensor: ', ENABLE_TAPPING);
console.log('Blowing Sensor: ', ENABLE_BLOWING);
console.log('Shaking Sensor: ', ENABLE_SHAKING);
