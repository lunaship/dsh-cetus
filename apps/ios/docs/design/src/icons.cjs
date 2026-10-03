const React=require('react');const {renderToStaticMarkup}=require('react-dom/server');const io=require('react-icons/io5');
module.exports=(name,size=22,color='currentColor',extra='')=>renderToStaticMarkup(React.createElement(io[name],{size,color})).replace('<svg','<svg '+extra+' style="flex:none;display:block"');
