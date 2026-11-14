globalThis.plugin = {
  async init(host) {
    const settings = await host.settings.all();
    host.action('label', async value => {
      await host.settings.set('label', value);
      host.patch('fixture-label', {text:value});
    });
    host.action('enabled', async value => await host.settings.set('enabled', value === 'true'));
    host.ui('settings.example', {id:'fixture-settings',type:'Column',children:[
      {id:'fixture-label',type:'Text',props:{text:settings.label}},
      {id:'fixture-input',type:'TextField',props:{text:settings.label,action:'label'}},
      {id:'fixture-switch',type:'Switch',props:{checked:String(settings.enabled),action:'enabled'}}
    ]});
    const activations = (await host.storage.get('activations') ?? 0) + 1;
    await host.storage.set('activations', activations);
  }
};
