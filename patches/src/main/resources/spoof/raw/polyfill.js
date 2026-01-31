if (!Object.hasOwn) {
  Object.hasOwn = function (obj, key) {
    if (obj == null) {
      throw new TypeError("Object.hasOwn called on null or undefined");
    }
    return Object.prototype.hasOwnProperty.call(obj, key);
  };
}

if (typeof URL === 'undefined') {
  globalThis.URL = function (url) {
    this.href = url;

    // naive parsing for hostname
    const match = url.match(/^(\w+):\/\/([^\/?#]+)(?:[\/?#]|$)/);
    this.protocol = match ? match[1] + ':' : '';
    this.hostname = match ? match[2] : '';
    this.port = ''; // optional
    this.pathname = url.replace(/^(\w+:\/\/[^\/?#]+)?/, '') || '/';
    this.search = '';
    this.hash = '';
  };
}

if (typeof window === 'undefined') {
  globalThis.window = {};
}

if (!window.location) {
  window.location = {
    _url: '',
    set href(url) {
      this._url = url;
      // parse the URL so hostname works
      this._parsed = new URL(url);
    },
    get href() {
      return this._url;
    },
    get hostname() {
      return this._parsed ? this._parsed.hostname : '';
    }
  };
}

if (typeof navigator === 'undefined') {
  globalThis.navigator = {
    userAgent: 'V8/Standalone',
    platform: 'V8',
    language: 'en-US',
    // add more properties if needed
  };
}