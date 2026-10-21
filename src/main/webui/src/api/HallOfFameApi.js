function convertResponse(response) {
  if (response.ok) {
    return response.json();
  }
  throw new Error(`Request error: ${response.status}`);
}

/**
 * Past finished races, most recent first. Returns an empty list on error so the view can render nothing.
 */
export async function fetchHallOfFame() {
  return fetch('/api/hall-of-fame', { headers: { Accept: 'application/json' } })
    .then(convertResponse)
    .catch((e) => {
      console.error(e);
      return [];
    });
}
