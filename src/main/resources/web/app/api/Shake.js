export default class Shake {
  constructor(options) {
    // feature detect
    this.hasDeviceMotion = 'ondevicemotion' in window;

    if (
      DeviceMotionEvent
            && typeof DeviceMotionEvent.requestPermission === 'function'
    ) {
      DeviceMotionEvent.requestPermission().catch((e) => console.error(e));
    }

    this.options = {
      threshold: 10, // default velocity threshold for shake to register
      timeout: 200, // default interval between events
    };

    if (typeof options === 'object') {
      for (const i in options) {
        if (options.hasOwnProperty(i)) {
          this.options[i] = options[i];
        }
      }
    }

    // use date to prevent multiple shakes firing
    this.lastTime = new Date();

    // accelerometer values
    this.lastX = null;
    this.lastY = null;
    this.lastZ = null;

    this.devicemotion = this.devicemotion.bind(this);
  }

  reset = () => {
    this.lastTime = new Date();
    this.lastX = null;
    this.lastY = null;
    this.lastZ = null;
  };

  start = (handler) => {
    this.reset();
    this.handler = handler;
    if (this.hasDeviceMotion) {
      window.addEventListener('devicemotion', this.devicemotion, false);
    }
  };

  stop = () => {
    if (this.hasDeviceMotion) {
      window.removeEventListener('devicemotion', this.devicemotion, false);
    }
    this.reset();
  };

  devicemotion = (e) => {
    const current = e.acceleration;
    let currentTime;
    let timeDifference;
    let deltaX = 0;
    let deltaY = 0;
    let deltaZ = 0;

    if ((this.lastX === null) && (this.lastY === null) && (this.lastZ === null)) {
      this.lastX = current.x;
      this.lastY = current.y;
      this.lastZ = current.z;
      return;
    }

    deltaX = Math.abs(this.lastX - current.x);
    deltaY = Math.abs(this.lastY - current.y);
    deltaZ = Math.abs(this.lastZ - current.z);

    const magnitude = Math.max(deltaX, deltaY, deltaZ);

    console.log('motion', magnitude);

    if (magnitude > this.options.threshold) {
      // calculate time in milliseconds since last shake registered
      currentTime = new Date();
      timeDifference = currentTime.getTime() - this.lastTime.getTime();

      if (timeDifference > this.options.timeout) {
        this.handler(Math.round(magnitude * 3));
        this.lastTime = new Date();
      }
    }

    this.lastX = current.x;
    this.lastY = current.y;
    this.lastZ = current.z;
  };
}
