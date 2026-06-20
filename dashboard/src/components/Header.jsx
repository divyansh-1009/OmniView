import React from 'react';
import logo from '../assets/Logo.png';

const Header = () => {
  return (
    <header className="header">
      <div className="container nav-container">
        <a href="#" className="logo">
          <img src={logo} alt="OmniView Logo" style={{ height: '32px', width: 'auto' }} />
        </a>
        <a href="#download" className="btn-primary">Get the App</a>
      </div>
    </header>
  );
};

export default Header;
