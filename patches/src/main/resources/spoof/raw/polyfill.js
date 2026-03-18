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
    const match = url.match(/^(https?:)\/\/([^\/?#:]+)(?::(\d+))?([^?#]*)?(\?[^#]*)?(#.*)?$/);
    this.protocol = match ? match[1] : '';
    this.hostname = match ? match[2] : '';
    this.port = match && match[3] ? match[3] : '';
    this.pathname = match && match[4] ? match[4] : '/';
    this.search = match && match[5] ? match[5] : '';
    this.hash = match && match[6] ? match[6] : '';
  };
}

if (typeof window === 'undefined') {
  globalThis.window = globalThis;
}

if (typeof self === 'undefined') {
  globalThis.self = globalThis;
}

if (!window.location) {
  const defaultHost = "https://localhost/";

  window.location = {
    _url: defaultHost,
    _parsed: new URL(defaultHost), // default, so hostname works
    set href(url) {
      this._url = url;
      this._parsed = new URL(url);
    },
    get href() {
      return this._url;
    },
    get protocol() {
      return this._parsed.protocol;
    },
    get hostname() {
      return this._parsed.hostname;
    },
    get port() {
      return this._parsed.port;
    },
    get host() {
      return this._parsed.hostname + (this._parsed.port ? ':' + this._parsed.port : '');
    },
    get origin() {
      return this._parsed.protocol + '//' + this.host;
    },
    get pathname() {
      return this._parsed.pathname;
    },
    get search() {
      return this._parsed.search;
    },
    get hash() {
      return this._parsed.hash;
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