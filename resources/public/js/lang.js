function switchLang(target) {
  document.cookie = "lang=" + target + "; path=/; max-age=31536000";
  var p = location.pathname;
  if (/^\/(fr|en)(\/|$)/.test(p)) {
    location.href = p.replace(/^\/(fr|en)/, "/" + target) + location.search + location.hash;
  } else {
    location.href = "/" + target + "/index.html";
  }
}
