self.addEventListener('fetch', (event) => {
  // Basic pass-through fetch handler to satisfy PWA requirements
  event.respondWith(fetch(event.request));
});
