const button = (id, text, action) => ({id, type: 'Button', props: {text, action}})

globalThis.plugin = {
  init(host) {
    const updateTitle = state => host.patch('now-playing', {
      text: state.track.title || 'No track playing'
    })
    host.action('previous', () => host.player('previous'))
    host.action('toggle', () => host.player('toggle'))
    host.action('next', () => host.player('next'))

    host.ui('ui.app', {
      id: 'shell',
      type: 'Column',
      props: {fillSize: 'true', padding: '24', spacing: '12'},
      children: [
        {id: 'now-playing', type: 'Text', props: {text: host.playerState().track.title || 'No track playing', fontSize: '24'}},
        {
          id: 'controls',
          type: 'Row',
          children: [
            button('previous-button', 'Previous', 'previous'),
            button('toggle-button', 'Play / pause', 'toggle'),
            button('next-button', 'Next', 'next')
          ]
        }
      ]
    })
    host.on('playback_changed', updateTitle)
  }
}
