const IS_TOUCH_DEVICE = (('ontouchstart' in window) ||
    (navigator.maxTouchPoints > 0) ||
    (navigator.msMaxTouchPoints > 0));

// Dashboard
export const CLICK_POWER = 30;
export const NB_CLICK_NEEDED_PER_USER = 200;
export const SHOW_TOP = 10;

// Mobile app
export const ENABLE_CLICK = !IS_TOUCH_DEVICE;
export const ENABLE_SWIPE = IS_TOUCH_DEVICE
export const ENABLE_BLOWING = false;

console.log("Swipe Sensor: ", ENABLE_SWIPE);
console.log("Click Sensor: ", ENABLE_CLICK);
console.log("Blowing Sensor: ", ENABLE_BLOWING);
