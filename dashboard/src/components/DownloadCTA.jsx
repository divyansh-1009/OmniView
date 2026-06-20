import React from 'react';

const DownloadCTA = () => {
  return (
    <section id="download" className="section" style={{ textAlign: 'center', backgroundColor: 'var(--bg-card)' }}>
      <div className="container">
        <h2 style={{ marginBottom: '1rem' }}>Experience True Digital Memory</h2>
        <p style={{ margin: '0 auto 2rem auto' }}>
          Download the OmniView APK directly and install it on your Android 7.0+ device to start building your semantic timeline.
        </p>
        <a href="/OmniView.apk" download="OmniView.apk" className="btn-primary" style={{ padding: '1rem 3rem', fontSize: '1.125rem' }}>
          Download APK 
        </a>
      </div>
    </section>
  );
};

export default DownloadCTA;
