import React from 'react';

const FeaturesSection = () => {
  return (
    <section className="section">
      <div className="container">
        <h2 style={{ textAlign: 'center', marginBottom: '3rem' }}>Why Choose OmniView?</h2>
        <div className="grid grid-cols-3">
          <div className="card">
            <h3 style={{ color: 'var(--text-primary)' }}>Uncompromising Privacy</h3>
            <p style={{ fontSize: '1rem' }}>
              Your life is yours. OmniView processes absolutely everything on your device. Zero cloud uploads, zero telemetry, and zero tracking. Nobody can see your data except you.
            </p>
          </div>
          <div className="card">
            <h3 style={{ color: 'var(--text-primary)' }}>Total Control</h3>
            <p style={{ fontSize: '1rem' }}>
              We put you in the driver's seat. Easily block sensitive applications like your banking or messaging apps from being remembered, and instantly wipe your entire history with a single tap.
            </p>
          </div>
          <div className="card">
            <h3 style={{ color: 'var(--text-primary)' }}>Battery Friendly</h3>
            <p style={{ fontSize: '1rem' }}>
              Intelligent design means your phone's battery life is protected. OmniView quietly organizes your memories only when it's convenient—so your device stays charged all day.
            </p>
          </div>
        </div>
      </div>
    </section>
  );
};

export default FeaturesSection;
