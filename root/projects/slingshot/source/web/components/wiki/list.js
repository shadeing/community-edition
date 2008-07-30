/*
 *** Alfresco.WikiList
*/
(function()
{
   Alfresco.WikiList = function(containerId)
   {
	   this.name = "Alfresco.WikiList";
      this.id = containerId;

      this.$parser = new Alfresco.WikiParser();
      this.$parser.URL = Alfresco.constants.URL_CONTEXT + "page/site/" + this.siteId + "/wiki-page?title=";
         
      /* Load YUI Components */
      Alfresco.util.YUILoaderHelper.require(["button", "container", "connection", "editor", "tabview"], this.componentsLoaded, this);
      return this;
   };
   
   Alfresco.WikiList.prototype = 
   {
      /**
   	  * Sets the current site for this component.
   	  * 
   	  * @property siteId
   	  * @type string
   	  */
   	setSiteId: function(siteId)
   	{
   		this.siteId = siteId;
   		return this;
   	},
   	
      /**
       * Fired by YUILoaderHelper when required component script files have
       * been loaded into the browser.
       *
       * @method onComponentsLoaded
       */
       componentsLoaded: function()
       {
          YAHOO.util.Event.onContentReady(this.id, this.init, this, true);
       },
       
       /**
        * Fired by YUI when parent element is available for scripting.
        * Initialises components, including YUI widgets.
        *
        * @method init
        */
       init: function()
       {
          this._initMouseOverListeners();
          
          // Render any mediawiki markup
          // TODO: look at doing this on the server
          var divs = YAHOO.util.Dom.getElementsByClassName('pageCopy', 'div');
          var div;
          for (var i=0; i < divs.length; i++)
          {
             div = divs[i];
             div.innerHTML = this.$parser.parse(div.innerHTML);
          }
       },
       
       _initMouseOverListeners: function()
       {
          var divs = YAHOO.util.Dom.getElementsByClassName('wikipage', 'div');
          for (var x=0; x < divs.length; x++) {
             YAHOO.util.Event.addListener(divs[x], 'mouseover', this.mouseOverHandler);
             YAHOO.util.Event.addListener(divs[x], 'mouseout', this.mouseOutHandler);
          }
       },
       
       mouseOverHandler: function(e)
       {
          var currentTarget = e.currentTarget;
          YAHOO.util.Dom.addClass(currentTarget, 'wikiPageSelected');
          // Display the action panel for this page
          var panel = YAHOO.util.Dom.getElementsByClassName('actionPanel', 'div', currentTarget)[0];
          if (panel)
          {
             panel.style.visibility = "visible";
          }
       },
       
       mouseOutHandler: function(e)
       {
          var currentTarget = e.currentTarget;
          YAHOO.util.Dom.removeClass(currentTarget, 'wikiPageSelected');
          // Hide the action panel 
          var panel = YAHOO.util.Dom.getElementsByClassName('actionPanel', 'div', currentTarget)[0];
          if (panel)
          {
             panel.style.visibility = "hidden";
          }
       }
      
      
   };
   
})();   
