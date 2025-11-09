/**
 * Icon Loader Utility Functions
 * Handles loading and displaying SVG icons
 */

window.TripWeather = window.TripWeather || {};
window.TripWeather.Utils = window.TripWeather.Utils || {};

window.TripWeather.Utils.IconLoader = {
    
    /**
     * Load SVG icon from file path and insert into container
     * @param {string} iconPath - Path to SVG icon file
     * @param {HTMLElement} container - Container element to insert icon into
     * @param {string} className - CSS class to add to the SVG element
     * @returns {Promise<SVGElement|null>} - SVG element or null if failed
     */
    loadSvgIcon: function(iconPath, container, className) {
        className = className || '';
        
        return fetch(iconPath)
            .then(function(response) {
                if (!response.ok) {
                    throw new Error('Network response was not ok');
                }
                return response.text();
            })
            .then(function(svgText) {
                const parser = new DOMParser();
                const svgDoc = parser.parseFromString(svgText, 'image/svg+xml');
                const svg = svgDoc.documentElement;
                
                if (className) {
                    svg.classList.add(className);
                }
                
                container.innerHTML = '';
                container.appendChild(svg);
                
                return svg;
            })
            .catch(function(error) {
                console.error('Failed to load SVG icon:', error);
                return null;
            });
    },

    /**
     * Load multiple icons and return a promise that resolves when all are loaded
     * @param {Array} iconConfigs - Array of {path, container, className} objects
     * @returns {Promise<Array>} - Array of loaded SVG elements
     */
    loadMultipleIcons: function(iconConfigs) {
        const promises = iconConfigs.map(function(config) {
            return this.loadSvgIcon(config.path, config.container, config.className);
        }.bind(this));
        
        return Promise.all(promises);
    },

    /**
     * Create an icon container with appropriate classes
     * @param {string} iconPath - Path to icon file
     * @param {string} containerClass - CSS class for the container
     * @param {string} iconClass - CSS class for the icon
     * @returns {HTMLElement} - Container element
     */
    createIconContainer: function(iconPath, containerClass, iconClass) {
        const container = document.createElement('span');
        container.className = containerClass;
        container.dataset.icon = iconPath;
        
        if (iconClass) {
            container.dataset.iconClass = iconClass;
        }
        
        return container;
    },

    /**
     * Initialize icons for a container by finding all elements with data-icon attributes
     * @param {HTMLElement} parentContainer - Container to search for icon elements
     * @returns {Promise<Array>} - Promise that resolves when all icons are loaded
     */
    initializeIcons: function(parentContainer) {
        const iconElements = parentContainer.querySelectorAll('[data-icon]');
        const iconConfigs = [];
        
        iconElements.forEach(function(element) {
            const iconPath = element.dataset.icon;
            const iconClass = element.dataset.iconClass || '';
            
            if (iconPath) {
                iconConfigs.push({
                    path: iconPath,
                    container: element,
                    className: iconClass
                });
            }
        });
        
        return this.loadMultipleIcons(iconConfigs);
    }
};
