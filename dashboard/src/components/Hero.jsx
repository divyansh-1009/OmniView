import React from 'react';

const Hero = () => {
  return (
    <section className="section animate-fade-in" style={{ paddingTop: '8rem', paddingBottom: '8rem', textAlign: 'center' }}>
      <div className="container" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
        <h1 style={{ maxWidth: '800px', margin: '0 auto 1.5rem auto' }}>
          Never forget a single moment of your digital life.
        </h1>
        <p style={{ maxWidth: '600px', margin: '0 auto 3rem auto', fontSize: '1.25rem' }}>
          OmniView gives you perfect, conversational recall of everything you've ever seen on your screen. Total privacy, absolute control, and zero cloud uploads.
        </p>
        <div style={{ display: 'flex', gap: '1rem', justifyContent: 'center' }}>
          <a href="#download" className="btn-primary">Download App</a>
          <a href="#problem" className="btn-secondary">Discover More</a>
        </div>
      </div>
    </section>
  );
};

export default Hero;
