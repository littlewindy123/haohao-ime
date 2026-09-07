// Website material studies, not downloadable Android theme definitions.
export const SKINS = Object.freeze({
  cream: { name: '好好原色', subtitle: '奶油白、薄荷绿，还有一点蜂蜜金。', kind: 'builtin', colors: ['#f7f2e5', '#a1c6b1', '#eab65d', '#c7c9bc'], ink: '#453b32' },
  blue: { name: '雾蓝', subtitle: '把清晨的清爽，留在指尖。', kind: 'builtin', colors: ['#e9f0f7', '#b5cbdc', '#88b5d3', '#9bafbf'], ink: '#263e53' },
  apricot: { name: '暖杏', subtitle: '温柔一点，也明亮一点。', kind: 'builtin', colors: ['#fff0e4', '#eac4ac', '#d9a180', '#c6ae9f'], ink: '#563829' },
  graphite: { name: '石墨', subtitle: '深一点的颜色，清楚一点的表达。', kind: 'builtin', colors: ['#9ba5ac', '#818c91', '#bbc4be', '#333b41'], ink: '#17232b' },
  taffy: { name: '永雏塔菲', subtitle: '今天也要，快乐整活。', kind: 'concept', atlas: 'keycap-taffy.webp', colors: ['#ffe9e6', '#e6a5ae', '#ecbb76', '#bd8e96'], ink: '#633943' },
  raiden: { name: '雷电将军', subtitle: '落指之间，自有锋芒。', kind: 'concept', atlas: 'keycap-raiden.webp', colors: ['#e7def4', '#ac93d1', '#d5b878', '#66567f'], ink: '#36234d' },
  yasuo: { name: '亚索', subtitle: '字随心走，如风自在。', kind: 'concept', atlas: 'keycap-yasuo.webp', colors: ['#dce8ea', '#8cb3bd', '#c9b17b', '#4d6d79'], ink: '#1a3a45' },
  nailong: { name: '奶龙', kind: 'concept', atlas: 'keycap-nailong.webp', colors: ['#fff4d3', '#edcf7e', '#efb445', '#bb9d5c'], ink: '#543c21' },
  kun: { name: '蔡徐坤', kind: 'concept', atlas: 'keycap-kun.webp', colors: ['#f4f2ec', '#bfc2c4', '#e6a668', '#626466'], ink: '#282b2d' },
  lanyangyang: { name: '懒羊羊', kind: 'concept', atlas: 'keycap-lanyangyang.webp', colors: ['#fff7e8', '#efd8b4', '#ecb974', '#bc9d7a'], ink: '#574024' },
});
export function skinFor(id) { return Object.hasOwn(SKINS, id) ? SKINS[id] : SKINS.cream; }
export function skinNotice(id) {
  return skinFor(id).kind === 'concept'
    ? '同人概念预览，尚未内置 App；非官方联名。'
    : 'App 已支持此主题；此处为 3D 材质示意，非手机截图。';
}
